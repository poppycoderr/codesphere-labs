"""容器内存记账：申请与触碰、匿名页与文件页、次缺页与主缺页、上限之下的回收与 OOM。在带内存上限的一次性容器里运行。"""
import mmap
import os
import sys

MB = 1024 * 1024
PAGE = os.sysconf("SC_PAGE_SIZE")


def status(key):
    with open("/proc/self/status") as f:
        for line in f:
            if line.startswith(key + ":"):
                return int(line.split()[1]) // 1024  # MB
    return -1


def faults():
    with open("/proc/self/stat") as f:
        parts = f.read().rsplit(")", 1)[1].split()
    return int(parts[7]), int(parts[9])  # minflt, majflt


def cg(name):
    with open("/sys/fs/cgroup/memory.stat") as f:
        for line in f:
            k, v = line.split()
            if k == name:
                return int(v) // MB
    return -1


def current():
    with open("/sys/fs/cgroup/memory.current") as f:
        return int(f.read()) // MB


def events(name):
    with open("/sys/fs/cgroup/memory.events") as f:
        for line in f:
            k, v = line.split()
            if k == name:
                return int(v)
    return -1


class Probe:
    def __init__(self):
        self.snap()

    def snap(self):
        self.vsz, self.rss, self.rss_file = status("VmSize"), status("VmRSS"), status("RssFile")
        self.minflt, self.majflt = faults()
        self.anon, self.file, self.shmem = cg("anon"), cg("file"), cg("shmem")

    def delta(self, key):
        o = (self.vsz, self.rss, self.rss_file, self.minflt, self.majflt, self.anon, self.file, self.shmem)
        self.snap()
        n = (self.vsz, self.rss, self.rss_file, self.minflt, self.majflt, self.anon, self.file, self.shmem)
        d = [b - a for a, b in zip(o, n)]
        print("%s\tVmSize %+d MB\tVmRSS %+d MB\tRssFile %+d MB\tminflt %+d\tmajflt %+d\tcgroup anon %+d MB\tcgroup file %+d MB\t其中 shmem %+d MB"
              % (key, d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7]), flush=True)
        return d


def touch(m, size, write):
    for off in range(0, size, PAGE):
        if write:
            m[off] = 1
        else:
            m[off]


def write_file(path, size_mb):
    block = os.urandom(MB)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    for _ in range(size_mb):
        os.write(fd, block)
    os.fsync(fd)
    os.close(fd)


def drop_cache(path):
    fd = os.open(path, os.O_RDONLY)
    os.posix_fadvise(fd, 0, 0, os.POSIX_FADV_DONTNEED)
    os.close(fd)


def read_file(path):
    fd = os.open(path, os.O_RDONLY)
    n = 0
    while True:
        b = os.read(fd, 4 * MB)
        if not b:
            break
        n += len(b)
    os.close(fd)
    return n // MB


def workingset(key):
    print("%s\tmemory.current %d MB\tanon %d MB\tfile %d MB\tactive_file %d MB\tinactive_file %d MB\tcurrent - inactive_file = %d MB"
          % (key, current(), cg("anon"), cg("file"), cg("active_file"), cg("inactive_file"), current() - cg("inactive_file")), flush=True)


def anon_map(size, huge):
    m = mmap.mmap(-1, size, flags=mmap.MAP_PRIVATE | mmap.MAP_ANONYMOUS)
    m.madvise(mmap.MADV_HUGEPAGE if huge else mmap.MADV_NOHUGEPAGE)
    return m


def accounting():
    print("env\tkernel=%s page_size=%d thp=%s memory.max=%d MB" % (os.uname().release, PAGE, open("/sys/kernel/mm/transparent_hugepage/enabled").read().strip(),
                                                                  int(open("/sys/fs/cgroup/memory.max").read()) // MB), flush=True)
    p = Probe()
    size = 256 * MB
    m = anon_map(size, False)
    p.delta("anon.map_256mb")
    touch(m, size, False)
    p.delta("anon.read_every_page")
    touch(m, size, True)
    p.delta("anon.write_every_page")
    touch(m, size, True)
    p.delta("anon.write_again")
    m.close()
    p.delta("anon.unmap")
    m = anon_map(size, True)
    touch(m, size, True)
    p.delta("anon.write_every_page_thp")
    m.close()
    p.snap()

    path = "/data/blob"
    write_file(path, 200)
    p.delta("file.write_200mb")
    drop_cache(path)
    p.delta("file.drop_cache")
    read_file(path)
    p.delta("file.read_cold")
    read_file(path)
    p.delta("file.read_again")
    drop_cache(path)
    p.snap()
    fd = os.open(path, os.O_RDONLY)
    fm = mmap.mmap(fd, 200 * MB, prot=mmap.PROT_READ)
    p.delta("file.mmap")
    touch(fm, 200 * MB, False)
    p.delta("file.mmap_touch_cold")
    fm.close()
    p.delta("file.munmap")
    fm = mmap.mmap(fd, 200 * MB, prot=mmap.PROT_READ)
    touch(fm, 200 * MB, False)
    p.delta("file.mmap_touch_warm")
    workingset("workingset.after_mmap_twice")
    fm.close()
    drop_cache(path)
    p.snap()
    fm = mmap.mmap(fd, 200 * MB, prot=mmap.PROT_READ)
    fm.madvise(mmap.MADV_RANDOM)
    for off in range(0, 200 * MB, 64 * PAGE):   # 每 256 KB 碰一页，共 800 页
        fm[off]
    p.delta("file.mmap_touch_cold_random_800_pages")
    fm.close()
    os.close(fd)
    read_file(path)
    read_file(path)
    workingset("workingset.after_read_twice")
    drop_cache(path)
    p.snap()
    write_file("/dev/shm/blob", 200)
    p.delta("shm.write_200mb")
    drop_cache("/dev/shm/blob")
    p.delta("shm.drop_cache")
    os.unlink("/dev/shm/blob")
    p.delta("shm.unlink")
    write_file("/data/big", 600)   # 给 under_limit 场景准备的文件，在上限宽松的容器里写好
    drop_cache("/data/big")


def under_limit():
    """上限 300 MB：先读一个事先准备好的 600 MB 文件，再申请 200 MB 匿名内存"""
    path = "/data/big"
    read_file(path)
    print("limit.after_read_600mb\tmemory.current %d MB\tfile %d MB\tanon %d MB\tmemory.events max=%d oom_kill=%d"
          % (current(), cg("file"), cg("anon"), events("max"), events("oom_kill")), flush=True)
    m = anon_map(200 * MB, False)
    touch(m, 200 * MB, True)
    print("limit.after_anon_200mb\tmemory.current %d MB\tfile %d MB\tanon %d MB\tmemory.events max=%d oom_kill=%d"
          % (current(), cg("file"), cg("anon"), events("max"), events("oom_kill")), flush=True)
    m.close()


def over_limit():
    """上限 300 MB：申请 400 MB 匿名内存并逐页写"""
    m = anon_map(400 * MB, False)
    print("oom.mapped_400mb\tVmSize 已增加，VmRSS %d MB\tmemory.current %d MB" % (status("VmRSS"), current()), flush=True)
    for off in range(0, 400 * MB, PAGE):
        m[off] = 1
        if off % (100 * MB) == 0 and off:
            print("oom.touched_%dmb\tVmRSS %d MB" % (off // MB, status("VmRSS")), flush=True)
    print("oom.survived\t不应到达这里", flush=True)


def shm_over_limit():
    """上限 300 MB：向 /dev/shm（tmpfs）写 400 MB"""
    block = os.urandom(MB)
    fd = os.open("/dev/shm/big", os.O_WRONLY | os.O_CREAT, 0o600)
    for i in range(400):
        os.write(fd, block)
        if (i + 1) % 100 == 0:
            print("shm_oom.written_%dmb\tVmRSS %d MB\tmemory.current %d MB\tshmem %d MB" % (i + 1, status("VmRSS"), current(), cg("shmem")), flush=True)
    print("shm_oom.survived\t不应到达这里", flush=True)


if __name__ == "__main__":
    {"accounting": accounting, "under_limit": under_limit, "over_limit": over_limit, "shm_over_limit": shm_over_limit}[sys.argv[1]]()
