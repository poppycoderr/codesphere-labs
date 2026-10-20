"""跨分片分页：各分片取 offset+limit 后归并的读取量；按分片平均分摊 offset 的错误结果；单列游标遇到同值时的遗漏与重复；
复合游标（排序列 + 唯一决胜键）；翻页期间插入新数据时 offset 与游标的差别。纯计算模拟，数据由固定种子生成。输出 TSV（键、说明）。"""
import heapq
import random

SHARDS, PER, PAGE = 4, 5000, 20
rng = random.Random(7)


def out(k, v):
    print(f"{k}\t{v}", flush=True)


# 每行：(created_at, id)，按 created_at 倒序、id 倒序展示；created_at 只到秒，大量重复
rows = [[] for _ in range(SHARDS)]
nid = 0
for s in range(SHARDS):
    for _ in range(PER):
        nid += 1
        rows[s].append((rng.randint(0, 2000), nid))
key = lambda r: (-r[0], -r[1])
for s in range(SHARDS):
    rows[s].sort(key=key)
truth = sorted([r for sh in rows for r in sh], key=key)
dup_ts = sum(1 for i in range(1, len(truth)) if truth[i][0] == truth[i - 1][0])
out("data", f"{SHARDS} 个分片、每片 {PER} 行、共 {len(truth)} 行；created_at 只到秒，与前一行同值的行有 {dup_ts} 个")


# 1. offset 分页：每个分片都要取 offset+limit 行
def offset_page(page):
    off = (page - 1) * PAGE
    fetched = [sh[: off + PAGE] for sh in rows]
    merged = list(heapq.merge(*fetched, key=key))[off: off + PAGE]
    return merged, sum(len(f) for f in fetched)


for page in (1, 50, 500):
    got, n = offset_page(page)
    ok = got == truth[(page - 1) * PAGE: page * PAGE]
    out(f"offset.{page}", f"第 {page} 页：每个分片取前 {(page - 1) * PAGE + PAGE} 行（不足则取全部），共读取 {n} 行、归并后丢弃 {n - PAGE} 行；结果正确：{ok}")

# 错误做法：把 offset 平均分给各分片
page = 50
off = (page - 1) * PAGE
wrong = sorted([r for sh in rows for r in sh[off // SHARDS: off // SHARDS + PAGE // SHARDS]], key=key)
right = truth[off: off + PAGE]
out("offset.split", f"把第 {page} 页的 offset 平均分给 {SHARDS} 个分片、每片取 {PAGE // SHARDS} 行：与正确结果相同的行 {len(set(wrong) & set(right))} / {PAGE}")


# 2. 游标分页：各分片只取 limit 行
def traverse(pred):
    """pred(row, cursor) 为真表示 row 在游标之后；返回遍历到的所有行与每页读取量"""
    seen, cursor, reads = [], None, 0
    while True:
        fetched = [[r for r in sh if cursor is None or pred(r, cursor)][:PAGE] for sh in rows]
        reads += sum(len(f) for f in fetched)
        page_rows = list(heapq.merge(*fetched, key=key))[:PAGE]
        if not page_rows:
            return seen, reads
        seen += page_rows
        cursor = page_rows[-1]
        if len(seen) > 2 * len(truth):
            return seen, reads


def report(name, seen, reads):
    ids = [r[1] for r in seen]
    missing = len(set(r[1] for r in truth) - set(ids))
    dups = len(ids) - len(set(ids))
    return f"{name}：遍历得到 {len(ids)} 行，遗漏 {missing} 行，重复 {dups} 行"


s1, r1 = traverse(lambda r, c: r[0] < c[0])
out("cursor.strict", report("只按 created_at，条件 created_at < 上一页最后一行", s1, r1))
s2, r2 = traverse(lambda r, c: r[0] <= c[0] and r != c)
name = "只按 created_at，条件 created_at <= 上一页最后一行（排除那一行本身）"
out("cursor.inclusive", report(name, s2, r2) + ("；已在同值的行之间反复翻页，实验在遍历量超过总行数两倍时停止" if len(s2) > 2 * len(truth) else ""))
s3, r3 = traverse(lambda r, c: (r[0], r[1]) < (c[0], c[1]))
out("cursor.composite", report("复合游标 (created_at, id) < 上一页最后一行", s3, r3) + f"；与全局排序逐行一致：{s3 == truth}")
pages = len(truth) // PAGE
out("cursor.cost", f"复合游标每页每个分片最多取 {PAGE} 行，遍历 {pages} 页共读取 {r3} 行；同样遍历用 offset 需要读取 {sum(SHARDS * min(PER, p * PAGE) for p in range(1, pages + 1))} 行")

# 3. 翻页期间插入新数据
base = truth[:]
p1 = base[:PAGE]
newest = (2001, nid + 1)                         # 用户翻页时插入一条最新的
after = [newest] + base
p2_offset = after[PAGE: 2 * PAGE]
cur = p1[-1]
p2_cursor = [r for r in after if (r[0], r[1]) < (cur[0], cur[1])][:PAGE]
out("insert.offset", f"读完第 1 页后插入一条更新的数据：offset 取第 2 页，第一行与第 1 页最后一行重复：{p2_offset[0] == p1[-1]}")
out("insert.cursor", f"复合游标取第 2 页：与第 1 页重复的行 {len(set(p1) & set(p2_cursor))} 个，紧接在第 1 页之后：{p2_cursor == base[PAGE: 2 * PAGE]}")
