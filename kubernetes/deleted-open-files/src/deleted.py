"""文件删除与打开句柄：一个进程一直开着日志文件，另一边删除、改名、清空这个文件，观察文件系统已用空间（df 的口径）与目录下文件占用（du 的口径）。在挂了 200 MB tmpfs 的一次性容器里运行。"""
import os
import subprocess
import sys

MB = 1024 * 1024
DATA = "/data"

WRITER = r'''
import os, sys
path, mode = sys.argv[1], sys.argv[2]
flags = os.O_WRONLY | os.O_CREAT | (os.O_APPEND if mode == "append" else 0)
fd = os.open(path, flags, 0o600)
block = b"x" * (1024 * 1024)
for line in sys.stdin:
    cmd = line.split()
    if cmd[0] == "w":
        for _ in range(int(cmd[1])):
            os.write(fd, block)
        print("ok", flush=True)
    elif cmd[0] == "q":
        break
'''


class Writer:
    def __init__(self, path, mode):
        self.p = subprocess.Popen([sys.executable, "-c", WRITER, path, mode], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)

    def write(self, mb):
        self.p.stdin.write("w %d\n" % mb)
        self.p.stdin.flush()
        self.p.stdout.readline()

    def fd_path(self):
        """写入进程打开的那个文件在 /proc 里的入口"""
        base = "/proc/%d/fd" % self.p.pid
        for n in os.listdir(base):
            target = os.readlink(os.path.join(base, n))
            if target.startswith(DATA):
                return os.path.join(base, n), target
        return None, None

    def stop(self):
        self.p.stdin.write("q\n")
        self.p.stdin.flush()
        self.p.wait()


def df_used():
    s = os.statvfs(DATA)
    return (s.f_blocks - s.f_bfree) * s.f_frsize // MB


def du():
    total = 0
    for root, _, files in os.walk(DATA):
        for f in files:
            total += os.lstat(os.path.join(root, f)).st_blocks * 512
    return total // MB


def row(key, what, extra=""):
    print("%s\t%s\t文件系统已用 %d MB\t目录下文件合计 %d MB\t目录内容 %s%s" % (key, what, df_used(), du(), sorted(os.listdir(DATA)), ("\t" + extra) if extra else ""), flush=True)


def main():
    # 一、删除一个仍被打开的文件
    w = Writer(DATA + "/app.log", "append")
    w.write(100)
    row("delete.before", "进程写了 100 MB 日志")
    os.unlink(DATA + "/app.log")
    fd, target = w.fd_path()
    row("delete.after_rm", "rm app.log", "写入进程的 fd 指向 %s" % target)
    w.write(50)
    row("delete.keep_writing", "进程继续写 50 MB")
    with open(fd, "rb") as f:
        head = f.read(4)
    row("delete.readable", "通过 /proc/<pid>/fd 仍可读到内容", "前 4 个字节 %r" % head)
    with open(fd, "w"):
        pass  # 以截断方式打开：清空已删除文件的内容
    row("delete.truncate_via_proc", "对 /proc/<pid>/fd/<n> 做截断")
    w.stop()
    row("delete.closed", "进程退出")

    # 二、清空一个仍被打开的文件：写入方是否以追加方式打开
    for mode in ("append", "plain"):
        path = DATA + "/%s.log" % mode
        w = Writer(path, mode)
        w.write(100)
        os.truncate(path, 0)
        w.write(1)
        st = os.stat(path)
        row("truncate.%s" % mode, "写 100 MB、清空、再写 1 MB（%s）" % ("O_APPEND" if mode == "append" else "未加 O_APPEND"),
            "文件长度 %d MB，实际占用 %d MB" % (st.st_size // MB, st.st_blocks * 512 // MB))
        w.stop()
        os.unlink(path)

    # 三、改名：写入进程跟着文件走，不跟着名字走
    w = Writer(DATA + "/app.log", "append")
    w.write(10)
    os.rename(DATA + "/app.log", DATA + "/app.log.1")
    w.write(10)
    row("rename", "写 10 MB、mv app.log app.log.1、再写 10 MB", "app.log.1 长度 %d MB，app.log 存在 = %s" % (os.stat(DATA + "/app.log.1").st_size // MB, os.path.exists(DATA + "/app.log")))
    w.stop()
    os.unlink(DATA + "/app.log.1")

    # 四、硬链接：删掉一个名字，另一个名字还在
    w = Writer(DATA + "/a.bin", "append")
    w.write(20)
    w.stop()
    os.link(DATA + "/a.bin", DATA + "/b.bin")
    os.unlink(DATA + "/a.bin")
    row("hardlink", "20 MB 的文件有两个名字，删掉其中一个", "剩余的名字的链接数 %d" % os.stat(DATA + "/b.bin").st_nlink)
    os.unlink(DATA + "/b.bin")
    row("clean", "全部删除并关闭之后")


if __name__ == "__main__":
    main()
