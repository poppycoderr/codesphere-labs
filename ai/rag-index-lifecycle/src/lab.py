"""知识库的增量更新：文档修改、删除、换嵌入模型、权限变化之后，索引里留下了什么。
嵌入是确定性的假实现（字符二元组哈希到 64 维再归一化），不调用任何模型。输出 TSV（键、说明）。"""
import hashlib
import math


def out(k, v):
    print(f"{k}\t{v}", flush=True)


def embed(text, model="emb-v1", dim=64):
    v = [0.0] * dim
    for i in range(len(text) - 1):
        h = hashlib.sha256((model + text[i:i + 2]).encode()).digest()
        v[h[0] % dim] += 1.0 if h[1] % 2 else -1.0
    n = math.sqrt(sum(x * x for x in v)) or 1.0
    return [x / n for x in v]


def cos(a, b):
    if len(a) != len(b):
        raise ValueError(f"维度不一致：{len(a)} 与 {len(b)}")
    return sum(x * y for x, y in zip(a, b))


def chunks(text):
    return [p.strip() for p in text.split("\n") if p.strip()]


class NaiveIndex:
    """只用内容哈希做键：同样的内容只存一份，但不知道它属于哪个文档的哪个位置"""

    def __init__(self):
        self.rows = {}

    def add(self, source, text, model="emb-v1"):
        for c in chunks(text):
            self.rows[hashlib.sha256(c.encode()).hexdigest()] = {"source": source, "text": c, "vec": embed(c, model)}

    def search(self, q, k=2, model="emb-v1"):
        qv = embed(q, model)
        return sorted(self.rows.values(), key=lambda r: -cos(qv, r["vec"]))[:k]


class ManagedIndex:
    """记录每个来源产生了哪些块；重建一个来源时删掉它不再产生的块；每条向量带上嵌入模型与权限组"""

    def __init__(self, model="emb-v1"):
        self.rows, self.by_source, self.model = {}, {}, model

    def upsert_source(self, source, text, group="all"):
        new_ids = set()
        for i, c in enumerate(chunks(text)):
            cid = f"{source}#{i}:{hashlib.sha256(c.encode()).hexdigest()[:8]}"
            new_ids.add(cid)
            self.rows[cid] = {"source": source, "text": c, "vec": embed(c, self.model), "model": self.model, "group": group}
        for old in self.by_source.get(source, set()) - new_ids:
            del self.rows[old]
        self.by_source[source] = new_ids

    def sync(self, sources):
        """全量同步：仓库里已经没有的来源，连同它的块一起删除"""
        for gone in set(self.by_source) - set(sources):
            for cid in self.by_source.pop(gone):
                del self.rows[cid]
        for s, (text, group) in sources.items():
            self.upsert_source(s, text, group)

    def search(self, q, groups, k=2, query_model=None):
        qm = query_model or self.model
        mixed = {r["model"] for r in self.rows.values()} - {qm}
        if mixed:
            raise ValueError(f"索引里有 {sorted(mixed)} 生成的向量，查询用的是 {qm}：拒绝混用")
        qv = embed(q, qm)
        visible = [r for r in self.rows.values() if r["group"] in groups]   # 先按权限过滤，再排序
        return sorted(visible, key=lambda r: -cos(qv, r["vec"]))[:k]


def fmt(rows):
    return " / ".join(f"{r['source']}:{r['text']}" for r in rows)


def main():
    v1 = "退货期限为签收后 7 天\n运费由买家承担"
    v2 = "退货期限为签收后 15 天\n运费由买家承担"
    q = "退货期限是多少天"

    naive = NaiveIndex()
    naive.add("policy.md", v1)
    naive.add("policy.md", v2)
    out("edit.naive", f"policy.md 从 7 天改成 15 天后重新导入，只按内容哈希做键：索引里 {len(naive.rows)} 块；查「{q}」得到 {fmt(naive.search(q))}")

    m = ManagedIndex()
    m.upsert_source("policy.md", v1)
    m.upsert_source("policy.md", v2)
    out("edit.managed", f"按来源记录块，重建时删掉不再产生的块：索引里 {len(m.rows)} 块；查「{q}」得到 {fmt(m.search(q, {'all'}))}")

    naive.add("old-faq.md", "旧版：退货期限为签收后 3 天")
    out("delete.naive", f"old-faq.md 从仓库删除之后，朴素索引只做增量导入：查「{q}」仍得到 {fmt(naive.search(q, k=3))}")
    m.sync({"policy.md": (v2, "all"), "old-faq.md": ("旧版：退货期限为签收后 3 天", "all")})
    before = fmt(m.search(q, {"all"}, k=3))
    m.sync({"policy.md": (v2, "all")})
    out("delete.managed", f"全量同步：删除前 {before}；删除后 {fmt(m.search(q, {'all'}, k=3))}")

    good = cos(embed(q, "emb-v1"), embed("退货期限为签收后 15 天", "emb-v1"))
    bad = cos(embed(q, "emb-v2"), embed("退货期限为签收后 15 天", "emb-v1"))
    out("model.mixed_score", f"同一模型下问题与答案的相似度 {good:.2f}；问题换成新模型嵌入、文档还是旧模型的向量 {bad:.2f}")
    try:
        m.search(q, {"all"}, query_model="emb-v2")
        out("model.guard", "未拒绝")
    except ValueError as e:
        out("model.guard", f"每条向量记录嵌入模型，查询时检查：{e}")
    try:
        cos(embed(q, "emb-v1", dim=64), embed(q, "emb-v3", dim=48))
    except ValueError as e:
        out("model.dim", f"维度不同的模型直接混用：{e}")

    m.sync({"policy.md": (v2, "all"), "salary.md": ("退货专员的薪资档位为 B3", "hr")})
    out("acl.filter", f"员工（只属于 all）查「退货专员的待遇」：{fmt(m.search('退货专员的待遇', {'all'}))}；人事（all、hr）查：{fmt(m.search('退货专员的待遇', {'all', 'hr'}, k=1))}")

    cache = {}
    def answer(query, groups, keyed_by_scope):
        key = (query, frozenset(groups)) if keyed_by_scope else query
        if key not in cache:
            cache[key] = fmt(m.search(query, groups, k=1))
        return cache[key]
    answer("退货专员的待遇", {"all", "hr"}, keyed_by_scope=False)
    out("cache.query_only", "答案缓存只用问题文本做键，人事先问过一次，员工再问：" + answer("退货专员的待遇", {"all"}, keyed_by_scope=False))
    cache.clear()
    answer("退货专员的待遇", {"all", "hr"}, keyed_by_scope=True)
    out("cache.scoped", "缓存键加上权限范围：员工再问：" + answer("退货专员的待遇", {"all"}, keyed_by_scope=True))


main()
