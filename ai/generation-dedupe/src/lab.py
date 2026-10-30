"""重复点击又生成了一次：生成结果缓存的竞态、过期与三态。假生成器记录调用次数，模拟昂贵、按次计费的模型调用。"""
import threading
import time
import uuid

import redis

r = redis.Redis(host="redis", decode_responses=True)


def out(key, text):
    print(f"{key}\t{text}")


class FakeModel:
    """每次调用睡 0.3 秒，计数；可以设定返回空答案或抛异常。"""

    def __init__(self):
        self.calls = 0
        self.lock = threading.Lock()
        self.mode = "ok"

    def generate(self, prompt):
        with self.lock:
            self.calls += 1
        time.sleep(0.3)
        if self.mode == "empty":
            return ""
        if self.mode == "error":
            raise RuntimeError("upstream 503")
        return f"answer({prompt})"


def burst(fn, n=5):
    res = [None] * n
    ts = [threading.Thread(target=lambda i=i: res.__setitem__(i, fn())) for i in range(n)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    return res


r.flushall()

# 一、先查缓存、没有就生成：5 次几乎同时的点击
m = FakeModel()


def naive(key, prompt):
    v = r.get(key)
    if v is not None:
        return v
    v = m.generate(prompt)
    r.set(key, v, ex=60)
    return v


res = burst(lambda: naive("naive:q1", "q1"))
out("naive.burst", f"5 次几乎同时的相同请求，先 GET 再生成再 SET：生成器被调用 {m.calls} 次，5 个结果相同={len(set(res)) == 1}")

# 二、EXISTS 之后再 GET
r.set("race:q1", "answer(q1)", px=50)
exists = r.exists("race:q1")
time.sleep(0.08)
got = r.get("race:q1")
out("exists.get", f"EXISTS 返回 {exists}，随后 GET 返回 {got!r}（中间键过期了）；直接 GET 并判断 None 只有一次往返、没有这个窗口")

# 三、状态和结果放两个键，各自过期
r.set("job:q1:status", "done", px=400)
r.set("job:q1:result", "answer(q1)", px=200)
time.sleep(0.25)
out("two-keys", f"状态键与结果键分开设置过期：250ms 后状态={r.get('job:q1:status')!r}、结果={r.get('job:q1:result')!r}；放进同一个哈希只有一个 TTL")

# 四、普通 SET 清掉 TTL
r.set("ttl:q1", "pending", ex=60)
before = r.ttl("ttl:q1")
r.set("ttl:q1", "answer(q1)")
plain = r.ttl("ttl:q1")
r.set("ttl:q2", "pending", ex=60)
r.set("ttl:q2", "answer(q2)", keepttl=True)
keep = r.ttl("ttl:q2")
out("set.ttl", f"写入前 TTL {before} 秒；普通 SET 回写结果后 TTL {plain}（-1 表示永不过期）；SET ... KEEPTTL 后 TTL {keep}")

# 五、空答案被缓存
m = FakeModel()
m.mode = "empty"
first = naive("empty:q1", "q1")
m.mode = "ok"
again = [naive("empty:q1", "q1") for _ in range(3)]
out("empty.cached", f"上游一次返回空答案：被缓存 60 秒，之后 3 次请求都拿到 {again[0]!r}，生成器只在第一次被调用（共 {m.calls} 次）")

# 六、三态 + 幂等键 + 租约令牌
DONE_TTL, FAIL_TTL, LEASE_MS = 60, 2, 500
FINISH = r.register_script("""
local cur = redis.call('HGET', KEYS[1], 'owner')
if cur ~= ARGV[1] then return 0 end
redis.call('HSET', KEYS[1], 'state', ARGV[2], 'value', ARGV[3])
redis.call('HDEL', KEYS[1], 'owner')
redis.call('EXPIRE', KEYS[1], ARGV[4])
return 1
""")
CLAIM = r.register_script("""
local st = redis.call('HGET', KEYS[1], 'state')
if st == 'done' then return {'done', redis.call('HGET', KEYS[1], 'value')} end
if st == 'failed' then return {'failed', redis.call('HGET', KEYS[1], 'value')} end
if st == 'pending' and redis.call('PTTL', KEYS[1]) > 0 then return {'wait', ''} end
redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1], 'state', 'pending', 'owner', ARGV[1])
redis.call('PEXPIRE', KEYS[1], ARGV[2])
return {'owner', ''}
""")


def single_flight(model, key, prompt, crash=False, stale_write=None):
    """租约内只有一个调用者生成；其他调用者轮询结果。失败写成 failed 并短暂保留，空答案按失败处理。"""
    me = uuid.uuid4().hex
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        st, val = CLAIM(keys=[key], args=[me, LEASE_MS])
        if st == "done":
            return val
        if st == "failed":
            return "FAILED:" + val                    # 短暂保留的失败：直接返回，不立即重试
        if st == "owner":
            if crash:
                return "CRASHED"                       # 拿到租约后进程消失，什么也没写
            try:
                v = model.generate(prompt)
                if not v:
                    raise ValueError("empty answer")
                ok = FINISH(keys=[key], args=[me, "done", v, DONE_TTL])
            except Exception as e:
                FINISH(keys=[key], args=[me, "failed", str(e), FAIL_TTL])
                return "FAILED:" + str(e)
            if stale_write is not None:
                stale_write.append(ok)
            return v
        time.sleep(0.05)
    return "TIMEOUT"


m = FakeModel()
res = burst(lambda: single_flight(m, "sf:q1", "q1"))
out("sf.burst", f"三态 + 幂等键：同样 5 次几乎同时的请求，生成器被调用 {m.calls} 次，5 个结果都是 {res[0]!r}={len(set(res)) == 1}")
out("sf.ttl", f"完成后记录 state={r.hget('sf:q1', 'state')}，TTL {r.ttl('sf:q1')} 秒")

m = FakeModel()
m.mode = "empty"
first = single_flight(m, "sf:q2", "q2")
st = r.hget("sf:q2", "state")
m.mode = "ok"
soon = single_flight(m, "sf:q2", "q2")
calls_soon = m.calls
time.sleep(2.1)
after = single_flight(m, "sf:q2", "q2")
out("sf.empty", f"上游返回空答案：记为 {st}（{first}），保留 {FAIL_TTL} 秒；期间再请求直接得到 {soon}（生成器仍是 {calls_soon} 次）；过期后重新生成得到 {after!r}，生成器共调用 {m.calls} 次")

m = FakeModel()
crashed = single_flight(m, "sf:q3", "q3", crash=True)
t0 = time.monotonic()
took = single_flight(m, "sf:q3", "q3")
waited = time.monotonic() - t0
out("sf.crash", f"第一个调用者拿到租约后消失（{crashed}）：第二个调用者等租约过期后接手，得到 {took!r}，等待在 0.3 到 1.2 秒之间={0.3 <= waited <= 1.2}")

# 七、旧持有者在租约过期后才写回
m = FakeModel()
r.delete("sf:q4")
slow = FakeModel()
orig = slow.generate
slow.generate = lambda p: (time.sleep(0.7), orig(p))[1]       # 比租约长
writes = []
t = threading.Thread(target=lambda: writes.append(single_flight(slow, "sf:q4", "q4-old", stale_write=writes)))
t.start()
time.sleep(0.6)                                              # 租约已过期
newer = single_flight(m, "sf:q4", "q4-new")
t.join()
out("sf.stale", f"慢的持有者生成用了 1 秒，租约 {LEASE_MS}ms 先过期，第二个调用者接手并写入 {newer!r}；旧持有者回来写入被拒绝={writes[0] == 0}，最终值 {r.hget('sf:q4', 'value')!r}")
