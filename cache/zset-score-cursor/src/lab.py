"""有序集合按 score 做游标分页：同分成员按成员名的字典序排列；只按 score 的排他游标遗漏同分成员，包含游标重复；
按 (score, member) 过滤与按字典序编码的二级索引都能不漏不重；翻页期间新增成员时按排名分页重复。输出 TSV（键、说明）。"""
import redis

r = redis.Redis(host="redis", decode_responses=True)
PAGE = 4


def out(k, v):
    print(f"{k}\t{v}", flush=True)


out("env", f"redis={r.info('server')['redis_version']} redis-py={redis.__version__}")
r.flushall()
scores = {f"p{i:02d}": s for i, s in enumerate([90, 80, 80, 80, 80, 80, 80, 70, 70, 60, 50, 50, 50, 40], 1)}
r.zadd("feed", scores)
allm = r.zrange("feed", "+inf", "-inf", byscore=True, desc=True, withscores=True)
out("ties.order", "按 score 倒序（ZRANGE ... BYSCORE REV）：" + " ".join(f"{m}:{int(s)}" for m, s in allm) + "；同分成员按成员名逆字典序排列")
truth = [m for m, _ in allm]


def walk(next_page):
    seen, cur = [], None
    for _ in range(3 * len(truth)):
        page = next_page(cur)
        if not page:
            break
        seen += [m for m, _ in page]
        cur = page[-1]
    return seen


def report(name, seen):
    return f"{name}：得到 {len(seen)} 个，遗漏 {sorted(set(truth) - set(seen))}，重复 {len(seen) - len(set(seen))} 个"


first = lambda: r.zrange("feed", "+inf", "-inf", byscore=True, desc=True, offset=0, num=PAGE, withscores=True)
excl = walk(lambda c: first() if c is None else r.zrange("feed", f"({c[1]:g}", "-inf", byscore=True, desc=True, offset=0, num=PAGE, withscores=True))
out("cursor.exclusive", report("游标只记 score，下一页取 (score", excl))
incl = walk(lambda c: first() if c is None else r.zrange("feed", f"{c[1]:g}", "-inf", byscore=True, desc=True, offset=0, num=PAGE, withscores=True)[1:])
out("cursor.inclusive", report("游标只记 score，下一页取 [score 并去掉第一个", incl) + ("；同分成员多于一页时原地打转" if len(incl) >= 3 * len(truth) - 1 or len(incl) > len(truth) else ""))


def composite(c):
    if c is None:
        return first()
    m0, s0 = c
    got, off = [], 0
    while len(got) < PAGE:      # 取包含同分的一段，跳过同分中已经返回过的成员（逆字典序下成员名不小于游标的）
        batch = r.zrange("feed", f"{s0:g}", "-inf", byscore=True, desc=True, offset=off, num=PAGE, withscores=True)
        if not batch:
            break
        off += len(batch)
        got += [(m, s) for m, s in batch if not (s == s0 and m >= m0)]
    return got[:PAGE]


comp = walk(composite)
out("cursor.composite", report("游标记 (score, member)，同分时按成员名继续", comp) + f"；与全量顺序一致：{comp == truth}")

# 字典序二级索引：score 全为 0，成员 = 反转后的定宽分数 + ':' + 原成员
for m, s in scores.items():
    r.zadd("feed:lex", {f"{999 - s:03d}:{m}": 0})
lex = walk(lambda c: [(m, 0) for m in r.zrange("feed:lex", "-" if c is None else f"({c[0]}", "+", bylex=True, offset=0, num=PAGE)])
lex_members = [x.split(":")[1] for x in lex]
out("cursor.lex", f"字典序二级索引（成员 = 定宽的 999-score + ':' + 原成员，ZRANGE BYLEX 的排他游标）：得到 {len(lex_members)} 个，"
                  f"顺序与按 score 倒序、同分按成员名正序一致：{lex_members == [m for m, _ in sorted(scores.items(), key=lambda kv: (-kv[1], kv[0]))]}")

# 翻页期间新增成员
p1 = r.zrange("feed", 0, PAGE - 1, desc=True)
r.zadd("feed", {"p99": 95})
p2_rank = r.zrange("feed", PAGE, 2 * PAGE - 1, desc=True)
out("insert.rank", f"按排名分页：第 1 页 {p1}；新增一个 95 分的成员后第 2 页 {p2_rank}，与第 1 页重复 {sorted(set(p1) & set(p2_rank))}")
last = (p1[-1], r.zscore("feed", p1[-1]))
p2_cur = composite(last)
out("insert.cursor", f"按 (score, member) 游标取第 2 页：{[m for m, _ in p2_cur]}，与第 1 页重复 {len(set(p1) & set(m for m, _ in p2_cur))} 个")
