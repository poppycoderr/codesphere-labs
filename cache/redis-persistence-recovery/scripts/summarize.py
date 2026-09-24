#!/usr/bin/env python3
"""汇总持久化实验的结果，断言文章中的关键结论。用法：summarize.py <输出目录>"""
import csv, re, statistics, sys
from pathlib import Path

out = Path(sys.argv[1])
failures = []
def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok: failures.append(msg)
def rows(name):
    return list(csv.DictReader((out / name).open(), delimiter="\t"))

# 崩溃矩阵
cm = []
for r in rows("crash-matrix.tsv"):
    present, maxp, beyond, dbsize = (int(x) for x in r["present_of_acked max_present beyond_acked dbsize"].split())
    acked = int(r["last_acked_seq"])
    cm.append(dict(cfg=r["config"], crash=r["crash"], run=r["run"], acked=acked, present=present, maxp=maxp, beyond=beyond,
                   lost=acked - present, ready=int(r["restart_ready_ms"]), gap_ms=int(r["killed_at_ms"]) - int(r["last_ack_at_ms"])))
def pick(cfg, crash): return [x for x in cm if x["cfg"] == cfg and x["crash"] == crash]
for cfg in ("none", "rdb-default"):
    for crash in ("process", "power"):
        x = pick(cfg, crash)[0]
        check(x["present"] == 0, f"{cfg} / {crash}：客户端确认了 {x['acked']:,} 条，重启后剩 {x['present']} 条（默认 save 规则 3600 1 / 300 100 / 60 10000，6 秒内还没有生成快照）" if cfg == "rdb-default" else f"{cfg} / {crash}：确认 {x['acked']:,} 条，重启后 0 条")
for cfg in ("aof-no", "aof-everysec", "aof-always"):
    x = pick(cfg, "process")[0]
    check(x["lost"] == 0 and x["present"] == x["maxp"], f"{cfg} / 进程崩溃（SIGKILL，page cache 保留）：确认 {x['acked']:,} 条，全部恢复")
ev = pick("aof-everysec", "power")
losses = [x["lost"] for x in ev]
check(all(x["present"] == x["maxp"] and x["beyond"] == 0 for x in ev), "aof-everysec / 断电：每次恢复出的都是从 1 开始、没有空洞的连续前缀（不是随机丢失）")
check(all(0 < l <= 2000 for l in losses), f"aof-everysec / 断电 {len(ev)} 次：每次都丢失已确认写入，分别 {losses} 条（每秒 1000 条，约 {min(losses) / 1000:.2f}—{max(losses) / 1000:.2f} 秒）")
al = pick("aof-always", "power")
check(all(x["lost"] == 0 for x in al), f"aof-always / 断电 {len(al)} 次：确认的写入全部恢复（{', '.join(str(x['acked']) for x in al)} 条）")
print(f"信息：重启到可服务（数据量 6 千条）{min(x['ready'] for x in cm)}—{max(x['ready'] for x in cm)}ms；客户端最后一次确认与 SIGKILL 的时间差 {min(x['gap_ms'] for x in cm)}—{max(x['gap_ms'] for x in cm)}ms（负数表示 kill 命令发出后仍有确认）")

# 写延迟
wl = {(r["config"], r["clients"]): r for r in rows("write-latency.tsv")}
for c in ("1", "32"):
    print("信息：" + f"{c} 个连接：" + "；".join(f"{cfg} {float(wl[(cfg, c)]['requests_per_second']):,.0f} 次/秒 p50 {wl[(cfg, c)]['p50_ms']}ms p99 {wl[(cfg, c)]['p99_ms']}ms" for cfg in ("none", "aof-no", "aof-everysec", "aof-always")))
a1, e1 = wl[("aof-always", "1")], wl[("aof-everysec", "1")]
check(float(a1["requests_per_second"]) < 0.7 * float(e1["requests_per_second"]) and float(a1["p50_ms"]) > float(e1["p50_ms"]),
      f"1 个连接：always {float(a1['requests_per_second']):,.0f} 次/秒、p50 {a1['p50_ms']}ms；everysec {float(e1['requests_per_second']):,.0f} 次/秒、p50 {e1['p50_ms']}ms")
a32, e32 = wl[("aof-always", "32")], wl[("aof-everysec", "32")]
ratio1 = float(a1["requests_per_second"]) / float(e1["requests_per_second"]); ratio32 = float(a32["requests_per_second"]) / float(e32["requests_per_second"])
check(ratio32 > ratio1, f"32 个连接时 always 的吞吐是 everysec 的 {ratio32:.0%}（1 个连接时 {ratio1:.0%}）：并发写入共享一次 fsync")

# multi-part AOF
mp = (out / "multipart-aof.txt").read_text()
before, after = mp.split("## 重写后")
check("appendonly.aof.1.base.rdb" in before and "appendonly.aof.1.incr.aof" in before, "开启 AOF 后目录里是 base（RDB 格式）+ incr + manifest 三类文件")
check(re.search(r"appendonly\.aof\.2\.base\.rdb", after) and re.search(r"appendonly\.aof\.\d+\.incr\.aof", after) and "appendonly.aof.1.base.rdb" not in after and "250000" in after,
      "BGREWRITEAOF 后生成新的 base，重写期间的写入进入新的 incr，旧文件被移除；DBSIZE 250,000 与写入一致")
print("信息：重写后的 manifest：" + " | ".join(l for l in after.splitlines() if l.startswith("file ")))

# fork 与写时复制
fc = dict(l.split(":", 1) for l in (out / "fork-cow.txt").read_text().splitlines())
ds = (out / "load-dataset.txt").read_text()
check(int(fc["busy_rdb_last_cow_size"]) > 5 * max(1, int(fc["idle_rdb_last_cow_size"])),
      f"100 万个 key（{ds.split(':')[1].split()[0]} 字节）BGSAVE：空闲时写时复制 {int(fc['idle_rdb_last_cow_size']) / 1e6:.1f} MB，fork {int(fc['idle_latest_fork_usec']) / 1000:.1f}ms；持续写入时写时复制 {int(fc['busy_rdb_last_cow_size']) / 1e6:.1f} MB，fork {int(fc['busy_latest_fork_usec']) / 1000:.1f}ms")

# 加载耗时
lt = {}
for r in rows("load-times.tsv"):
    sec = [float(x) for x in re.findall(r"([\d.]+) seconds", r["log"])]
    files = [l.split() for l in (out / f"load-{r['file']}-files.txt").read_text().splitlines()]
    want = (lambda f: f.endswith("dump.rdb")) if r["file"] == "rdb" else (lambda f: "appendonlydir/" in f)
    size = sum(int(f[4]) for f in files if want(f[-1]))
    lt[r["file"]] = (int(r["dbsize"]), max(sec), size)  # 日志同时有分文件与总计两行，取总计
    print(f"信息：{r['file']}：DBSIZE {r['dbsize']}，加载 {max(sec):.3f}s，文件 {size / 1e6:.1f} MB")
check(all(v[0] == 1_000_000 for v in lt.values()), "四种文件都恢复出 1,000,000 个 key")
check(lt["rdb"][1] < lt["aof-incr-only"][1] and lt["aof-rdb-preamble"][1] < lt["aof-incr-only"][1] and lt["rdb"][2] < lt["aof-incr-only"][2],
      f"RDB（{lt['rdb'][1]:.2f}s）与带 RDB 前导的 AOF（{lt['aof-rdb-preamble'][1]:.2f}s）加载快于只有 incr 的 AOF（{lt['aof-incr-only'][1]:.2f}s）与纯命令 AOF（{lt['aof-rewritten-plain'][1]:.2f}s）")

# 截断与损坏
t_def = (out / "corrupt-truncated-default.txt").read_text()
check("dbsize=9999" in t_def and "Truncating the AOF" in t_def, "incr 文件尾部截掉 5 字节：默认配置（aof-load-truncated yes）截掉半条命令后启动，DBSIZE 9,999")
t_strict = (out / "corrupt-truncated-strict.txt").read_text()
check("PONG" not in t_strict, "同样的文件，aof-load-truncated no：拒绝启动")
hd = (out / "corrupt-header-start.txt").read_text()
check("PONG" not in hd and "Bad file format" in hd, "第 5000 条命令的命令头被写入 7 个字节的垃圾：默认配置拒绝启动（Bad file format）")
fix = (out / "corrupt-header-fix.txt").read_text()
after_fix = int(re.search(r"dbsize_after_fix=(\d+)", fix).group(1))
check(after_fix == 5000, f"redis-check-aof --fix 从损坏处截断到文件末尾，启动后只剩 {after_fix:,} / 10,000 个 key")
sv = dict(l.split("=", 1) for l in (out / "corrupt-value-silent.txt").read_text().splitlines() if "=" in l and l.split("=")[0] in ("dbsize", "c:4999", "c:5000", "c:5001"))
check(sv["dbsize"] == "10000" and sv["c:5000"].startswith("GARBAGE") and sv["c:4999"].startswith("4999:"),
      f"c:5000 的值内部被写入 7 个字节：正常启动、DBSIZE 10,000，c:5000 的值变成「{sv['c:5000'][:16]}…」——AOF 没有逐条校验，值内部的损坏不会被发现")

# BACKUP
br = {r["prefix"]: r["keys"] for r in rows("backup-restored.tsv")}
bk = (out / "backup.txt").read_text()
check(br["a"] == "1000" and br["b"] == "1000" and br["c"] == "0",
      "BACKUP START 之前写 a、START 与 SEAL 之间写 b、SEAL 之后写 c：用 preload-file 在新实例恢复出 a 1,000、b 1,000、c 0，即 SEAL 那一刻的数据")
check(bk.strip().splitlines()[-1].strip() == "0", "误执行 FLUSHALL 后，原实例重启重放 AOF，DBSIZE 为 0：AOF 忠实记录了误操作，它不是备份")

if failures:
    sys.exit(f"{len(failures)} 项断言失败")
