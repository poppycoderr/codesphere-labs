"""用量记账：流式片段不是 token、重试与回退、回调重复注册、用量事件重复投递，以及按响应 ID 幂等入账与对账。
假供应商自己保存一份真实账单（billed），用来对照应用侧账本。"""
import sqlite3


def out(key, text):
    print(f"{key}\t{text}")


PRICE = {"big": 10, "small": 1}                 # 每千个输出 token 的价格（虚构单位）


class Timeout(Exception):
    pass


class Provider:
    """每次调用生成一个响应 ID；按实际生成的 token 计费，不管客户端有没有收到 usage。"""

    def __init__(self):
        self.billed = {}                         # 响应 ID -> (模型, 输入, 输出)
        self.seq = 0

    def stream(self, model, prompt_tokens, chunks, tokens_per_chunk, fail_after=None, redeliver=False):
        self.seq += 1
        rid = f"resp-{self.seq}"
        produced = 0
        try:
            for i, n in enumerate(chunks):
                if fail_after is not None and i == fail_after:
                    self.billed[rid] = (model, prompt_tokens, produced)
                    raise Timeout(rid)
                produced += tokens_per_chunk[i]
                yield {"type": "delta", "id": rid, "text": "x" * n}
        except GeneratorExit:                    # 客户端断开：已经生成的部分照样计费
            self.billed[rid] = (model, prompt_tokens, produced)
            raise
        self.billed[rid] = (model, prompt_tokens, produced)
        usage = {"type": "usage", "id": rid, "model": model, "input": prompt_tokens, "output": produced}
        yield usage
        if redeliver:
            yield dict(usage)

    def get_usage(self, rid):
        model, i, o = self.billed[rid]
        return {"id": rid, "model": model, "input": i, "output": o}


def total_billed(p):
    return sum(o for _, _, o in p.billed.values())


def cost(rows):
    return sum(PRICE[m] * o for m, o in rows) / 1000


CHUNKS = [3, 5, 1, 7, 2, 4, 6, 2, 3, 5, 1, 4]                  # 每个片段的字符数
TOKENS = [2, 3, 1, 4, 2, 3, 3, 2, 2, 4, 1, 4]                  # 每个片段实际对应的 token 数

# 一、片段数不是 token 数
p = Provider()
events = list(p.stream("big", 120, CHUNKS, TOKENS))
deltas = [e for e in events if e["type"] == "delta"]
usage = [e for e in events if e["type"] == "usage"][0]
out("chunks", f"流式返回 {len(deltas)} 个文本片段、{sum(len(e['text']) for e in deltas)} 个字符；最后的 usage 事件：输入 {usage['input']}、输出 {usage['output']} 个 token")

# 二、客户端中途断开：拿不到 usage，供应商照样计费
p = Provider()
got = []
for e in p.stream("big", 120, CHUNKS, TOKENS):
    got.append(e)
    if len(got) == 5:
        break                                   # 用户关掉了页面
out("cancel", f"收到 5 个片段后客户端断开：应用收到的 usage 事件 {sum(1 for e in got if e['type'] == 'usage')} 个；供应商记账输出 {p.billed['resp-1'][2]} 个 token")


# 三、朴素记账：只在成功时从 usage 事件入账，回调列表在每次尝试时追加
def naive_call(p, ledger, callbacks, attempts):
    for model, fail_after, redeliver in attempts:
        callbacks.append(lambda e, m=model: ledger.append((m, e["output"])))   # 每次尝试都注册一遍
        try:
            for e in p.stream(model, 120, CHUNKS, TOKENS, fail_after=fail_after, redeliver=redeliver):
                if e["type"] == "usage":
                    for cb in callbacks:
                        cb(e)
            return
        except Timeout:
            continue


p, ledger = Provider(), []
naive_call(p, ledger, [], [("big", 6, False), ("big", None, False)])
out("naive.retry", f"第一次尝试输出 6 个片段后超时、重试成功：账本 {len(ledger)} 条、输出 {sum(o for _, o in ledger)} 个 token（两条都是第二次尝试的 usage，回调注册了两次；第一次没有入账）；供应商实际计费 {total_billed(p)}（第一次 {p.billed['resp-1'][2]} + 第二次 {p.billed['resp-2'][2]}）")
p, ledger = Provider(), []
naive_call(p, ledger, [], [("big", None, True)])
out("naive.redeliver", f"usage 事件被重复投递一次：账本 {len(ledger)} 条、输出 {sum(o for _, o in ledger)}；供应商计费 {total_billed(p)}")
p, ledger = Provider(), []
naive_call(p, ledger, [], [("big", 3, False), ("small", None, False)])
out("naive.fallback", f"大模型输出 3 个片段后超时、回退到小模型成功：账本 {ledger}，按记录算费用 {cost(ledger):.3f}；按供应商账单算 {cost([(m, o) for m, _, o in p.billed.values()]):.3f}")


# 四、按响应 ID 幂等入账 + 对账
def ledger_db():
    db = sqlite3.connect(":memory:")
    db.execute("CREATE TABLE usage (request_id TEXT, attempt INT, response_id TEXT PRIMARY KEY, model TEXT, input INT, output INT, source TEXT)")
    return db


def record(db, req, attempt, rid, model, i, o, source):
    """同一个响应只有一行；供应商数据覆盖估算，重复投递不产生新行。"""
    db.execute("""INSERT INTO usage VALUES (?,?,?,?,?,?,?)
                  ON CONFLICT(response_id) DO UPDATE SET input=excluded.input, output=excluded.output, source=excluded.source
                  WHERE usage.source <> 'provider'""", (req, attempt, rid, model, i, o, source))


def good_call(p, db, req, attempts):
    for n, (model, fail_after, redeliver) in enumerate(attempts, 1):
        seen = 0
        rid = None
        try:
            for e in p.stream(model, 120, CHUNKS, TOKENS, fail_after=fail_after, redeliver=redeliver):
                rid = e["id"]
                if e["type"] == "delta":
                    if seen == 0:
                        record(db, req, n, rid, model, 120, 0, "pending")      # 拿到响应 ID 就先登记
                    seen += 1
                else:
                    record(db, req, n, rid, model, e["input"], e["output"], "provider")
            return
        except Timeout as t:
            record(db, req, n, str(t), model, 120, -1, "pending")
            continue


def reconcile(p, db):
    rows = db.execute("SELECT response_id FROM usage WHERE source = 'pending'").fetchall()
    for (rid,) in rows:
        u = p.get_usage(rid)
        record(db, None, None, rid, u["model"], u["input"], u["output"], "provider")
    return len(rows)


for name, attempts in (("retry", [("big", 6, False), ("big", None, False)]), ("redeliver", [("big", None, True)]),
                       ("fallback", [("big", 3, False), ("small", None, False)])):
    p, db = Provider(), ledger_db()
    good_call(p, db, "req-1", attempts)
    pending = reconcile(p, db)
    rows = db.execute("SELECT attempt, model, output, source FROM usage ORDER BY attempt").fetchall()
    out(f"good.{name}", f"按响应 ID 幂等入账、断开或超时的响应事后对账（{pending} 条）：账本 {[(a, m, o) for a, m, o, _ in rows]}，"
                        f"输出合计 {sum(r[2] for r in rows)}、费用 {cost([(r[1], r[2]) for r in rows]):.3f}；供应商计费 {total_billed(p)}、费用 {cost([(m, o) for m, _, o in p.billed.values()]):.3f}")
