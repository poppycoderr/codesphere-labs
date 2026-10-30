"""Redis Stream 消费组：投递、处理、确认是三个状态。每个场景输出一行「键<TAB>事实」。"""
import time

import redis

r = redis.Redis(host="redis", decode_responses=True)
S, G = "orders", "billing"


def out(key, text):
    print(f"{key}\t{text}")


def fresh(stream=S):
    r.delete(stream, "orders:dead")
    r.xgroup_create(stream, G, id="0", mkstream=True)


def read(consumer, n=10, start=">"):
    res = r.xreadgroup(G, consumer, {S: start}, count=n)
    return [mid for _, msgs in res for mid, _ in msgs] if res else []


# 一、取到不等于处理完
fresh()
ids = [r.xadd(S, {"order": str(i)}) for i in range(3)]
got = read("c1")
p = r.xpending(S, G)
out("pending", f"c1 用 XREADGROUP 读到 {len(got)} 条、没有 XACK：待确认 {p['pending']} 条，持有者 {[(c['name'], c['pending']) for c in p['consumers']]}；XLEN = {r.xlen(S)}")

# 二、c1 消失，消息不会自动给别人
again = read("c2")
out("no-redeliver", f"c1 不再出现，c2 用 '>' 读新消息：拿到 {len(again)} 条（已投递未确认的消息不会自动重投）")

# 三、重启后读自己的待确认
mine = read("c1", start="0")
out("own-pending", f"c1 重启后用 ID '0' 读自己的待确认列表：拿到 {len(mine)} 条，与之前相同={mine == got}")

# 四、空闲超时后接管
time.sleep(0.6)
nxt, claimed, deleted = r.xautoclaim(S, G, "c2", min_idle_time=500, start_id="0-0")
info = r.xpending_range(S, G, min="-", max="+", count=10)
out("autoclaim", f"空闲超过 500ms 后 c2 用 XAUTOCLAIM 接管：{len(claimed)} 条，现在的持有者 {sorted({i['consumer'] for i in info})}，投递次数 {[i['times_delivered'] for i in info]}（首次读取、c1 重读、c2 接管各算一次）")

# 五、原持有者其实没死，只是慢：它的 XACK 照样生效
acked = r.xack(S, G, got[0])
out("late-ack", f"c2 接管之后，c1 处理完第 1 条才 XACK：返回 {acked}（不检查当前持有者是谁）；c2 处理完再 XACK 同一条返回 {r.xack(S, G, got[0])}，这条消息已经被两个消费者各处理过一次")
r.xack(S, G, *got[1:])

# 六、毒消息：每次处理都失败
fresh()
bad = r.xadd(S, {"order": "bad"})
MAX = 3
for attempt in range(5):
    if attempt == 0:
        read("c1")
    else:
        time.sleep(0.25)
        r.xautoclaim(S, G, "c1", min_idle_time=200, start_id="0-0")
    d = r.xpending_range(S, G, min=bad, max=bad, count=1)[0]["times_delivered"]
    if d >= MAX:
        r.xadd("orders:dead", {"id": bad, "deliveries": d})
        r.xack(S, G, bad)
        break
out("poison", f"一条每次都处理失败的消息：投递次数到 {d} 次后转入死信流并确认；待确认 {r.xpending(S, G)['pending']} 条，死信流 {r.xlen('orders:dead')} 条")

# 七、裁剪掉还没确认的消息
fresh()
ids = [r.xadd(S, {"order": str(i)}) for i in range(5)]
read("c1")
approx = r.xtrim(S, maxlen=2)
len_approx = r.xlen(S)
r.xtrim(S, maxlen=2, approximate=False)
time.sleep(0.3)
nxt, claimed, deleted = r.xautoclaim(S, G, "c2", min_idle_time=200, start_id="0-0")
out("trim", f"5 条都已投递未确认时：近似裁剪 XTRIM MAXLEN ~ 2 删除 {approx} 条（XLEN {len_approx}），精确裁剪 MAXLEN 2 后 XLEN = {r.xlen(S)}；XAUTOCLAIM 接管 {len(claimed)} 条，另报告 {len(deleted)} 条已被删除（消息体没了，待确认记录随之清掉）；待确认剩 {r.xpending(S, G)['pending']} 条")

# 八、确认不删除消息
fresh()
r.xadd(S, {"order": "x"})
m = read("c1")
r.xack(S, G, *m)
out("ack-keeps", f"XACK 之后：待确认 {r.xpending(S, G)['pending']} 条，XLEN 仍是 {r.xlen(S)}（确认只清待确认记录，消息要靠 MAXLEN、MINID 或 XDEL 删除）")
out("env", "redis " + r.info("server")["redis_version"])
