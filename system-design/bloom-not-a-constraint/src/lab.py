"""布隆过滤器的「一定不存在」只对已经加入过滤器的元素成立：过滤器由快照构建、写路径没有同步、重建期间，都会对已存在的数据说不存在；
两个并发请求都查过滤器再插入时，只有数据库的唯一约束能挡住重复。SQLite 与标准库实现，交错由闩锁固定。输出 TSV（键、说明）。"""
import hashlib
import math
import sqlite3
import threading


class Bloom:
    def __init__(self, n, p):
        self.m = math.ceil(-n * math.log(p) / math.log(2) ** 2)
        self.k = max(1, round(self.m / n * math.log(2)))
        self.bits = bytearray((self.m + 7) // 8)

    def _idx(self, key):
        h = hashlib.sha256(key.encode()).digest()
        a, b = int.from_bytes(h[:8], "big"), int.from_bytes(h[8:16], "big")
        return [(a + i * b) % self.m for i in range(self.k)]

    def add(self, key):
        for i in self._idx(key):
            self.bits[i // 8] |= 1 << (i % 8)

    def might_contain(self, key):
        return all(self.bits[i // 8] >> (i % 8) & 1 for i in self._idx(key))


def out(k, v):
    print(f"{k}\t{v}", flush=True)


out("env", f"sqlite={sqlite3.sqlite_version}")
db = sqlite3.connect(":memory:", check_same_thread=False, isolation_level=None)
db.execute("CREATE TABLE users_loose (email TEXT)")
db.execute("CREATE TABLE users (email TEXT UNIQUE)")
for i in range(10000):
    db.execute("INSERT INTO users VALUES (?)", (f"u{i}@example.com",))

# 1. 由快照构建：之后写入的数据过滤器不知道
bf = Bloom(20000, 0.01)
for (e,) in db.execute("SELECT email FROM users"):
    bf.add(e)
out("snapshot.added", f"过滤器由 10000 个已有邮箱构建：已加入的邮箱里被判为不存在的 {sum(not bf.might_contain(f'u{i}@example.com') for i in range(10000))} 个")
for i in range(10000, 10100):
    db.execute("INSERT INTO users VALUES (?)", (f"u{i}@example.com",))      # 写路径没有同步过滤器
neg = sum(not bf.might_contain(f"u{i}@example.com") for i in range(10000, 10100))
out("snapshot.later", f"构建之后又写入 100 个，写路径没有更新过滤器：这 100 个已存在的邮箱，被判为「一定不存在」的 {neg} 个")

# 2. 重建期间：新过滤器还没加载完
rebuilt = Bloom(20000, 0.01)
rows = [e for (e,) in db.execute("SELECT email FROM users ORDER BY rowid")]
for e in rows[:5000]:
    rebuilt.add(e)
out("rebuild.partial", f"重建到一半就切换：已存在的 {len(rows)} 个邮箱里，被判为不存在的 {sum(not rebuilt.might_contain(e) for e in rows)} 个")

# 3. 并发的「先查过滤器，再插入」
def race(table):
    shared = Bloom(1000, 0.01)
    barrier = threading.Barrier(2)
    results = []
    lock = threading.Lock()

    def register(name):
        email = "new@example.com"
        absent = not shared.might_contain(email)
        barrier.wait()                                  # 两个请求都已经查过过滤器
        if absent:
            try:
                with lock:                               # sqlite 连接不能并发使用，串行执行写入
                    db.execute(f"INSERT INTO {table} VALUES (?)", (email,))
                shared.add(email)
                results.append("插入成功")
            except sqlite3.IntegrityError as e:
                results.append(f"被唯一约束拒绝（{e}）")

    ts = [threading.Thread(target=register, args=(n,)) for n in ("请求1", "请求2")]
    [t.start() for t in ts]; [t.join() for t in ts]
    n = db.execute(f"SELECT COUNT(*) FROM {table} WHERE email='new@example.com'").fetchone()[0]
    return "，".join(f"{r} {results.count(r)} 个" for r in sorted(set(results))), n


res, n = race("users_loose")
out("race.no_unique", f"没有唯一约束的表：两个请求都查到「一定不存在」，结果：{res}；表里同一邮箱 {n} 行")
res, n = race("users")
out("race.unique", f"有唯一约束的表：两个请求的结果：{res}；表里同一邮箱 {n} 行")
