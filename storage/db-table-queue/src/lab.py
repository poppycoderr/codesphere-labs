"""用数据库表当队列：自增 ID 与插入时间的分配顺序不是提交顺序；按「大于上次最大值」拉取会永久跳过晚提交的行；
按状态认领（FOR UPDATE SKIP LOCKED）、租约过期重新认领。两个会话的交错由代码顺序固定。输出 TSV（键、说明）。"""
import pymysql


def conn():
    return pymysql.connect(host="mysql", user="root", password="example_password", database="labs", autocommit=False)


def out(k, v):
    print(f"{k}\t{v}", flush=True)


admin = conn()
with admin.cursor() as c:
    c.execute("SELECT VERSION(), @@innodb_autoinc_lock_mode, @@transaction_isolation")
    v, mode, iso = c.fetchone()
out("env", f"mysql={v} innodb_autoinc_lock_mode={mode} transaction_isolation={iso}")


def reset():
    with admin.cursor() as c:
        c.execute("DROP TABLE IF EXISTS jobs")
        c.execute("""CREATE TABLE jobs (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            payload VARCHAR(40) NOT NULL,
            created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
            status VARCHAR(12) NOT NULL DEFAULT 'pending',
            locked_by VARCHAR(12) NULL,
            locked_until DATETIME(6) NULL,
            KEY idx_status (status, id))""")
    admin.commit()


def insert(cx, payload):
    with cx.cursor() as c:
        c.execute("INSERT INTO jobs (payload) VALUES (%s)", (payload,))
        return c.lastrowid


def poll_after(last, col="id"):
    """消费者：只拉取「大于上次处理到的最大值」的行"""
    cx = conn()
    with cx.cursor() as c:
        c.execute(f"SELECT id, payload, {col} FROM jobs WHERE {col} > %s ORDER BY {col}", (last,))
        rows = c.fetchall()
    cx.close()
    return rows


# 1. 按自增 ID 拉取
reset()
a, b = conn(), conn()
ida = insert(a, "A-早分配晚提交")        # 会话 A 先拿到 id，但事务还没提交
idb = insert(b, "B-晚分配早提交")
b.commit()
seen = poll_after(0)
last = max(r[0] for r in seen)
out("id.first_poll", f"A 拿到 id={ida} 未提交，B 拿到 id={idb} 已提交；消费者第一次拉取看到 {[r[1] for r in seen]}，记下最大 id={last}")
a.commit()
seen2 = poll_after(last)
with admin.cursor() as c:
    admin.commit(); c.execute("SELECT id, payload FROM jobs ORDER BY id"); allrows = c.fetchall()
out("id.second_poll", f"A 提交之后，消费者按 id > {last} 拉取看到 {[r[1] for r in seen2]}；表里实际有 {[r[1] for r in allrows]}，id={ida} 永远不会被拉到")

# 回滚留下空洞
c3 = conn(); gap = insert(c3, "回滚的任务"); c3.rollback(); c3.close()
c4 = conn(); nxt = insert(c4, "下一条"); c4.commit(); c4.close()
out("id.gap", f"一个事务拿到 id={gap} 后回滚，下一条拿到 id={nxt}：自增值不回收，id 有空洞，「连续」不能用来判断是否漏了")

# 2. 按插入时间拉取：同样的问题
reset()
a, b = conn(), conn()
insert(a, "A-早插入晚提交"); insert(b, "B-晚插入早提交"); b.commit()
seen = poll_after("1970-01-01", col="created_at")
wm = seen[-1][2]
a.commit()
seen2 = poll_after(wm, col="created_at")
out("ts.poll", f"按 created_at 拉取：第一次看到 {[r[1] for r in seen]}，A 提交后按 created_at > 上次最大值 看到 {[r[1] for r in seen2]}（created_at 在插入时取值，不是提交时）")

# 3. 按状态认领：FOR UPDATE SKIP LOCKED
reset()
p = conn()
for i in range(4):
    insert(p, f"job-{i + 1}")
p.commit()
late = conn(); late_id = insert(late, "job-晚提交")       # 未提交


def claim(cx, worker, n=2):
    with cx.cursor() as c:
        c.execute("SELECT id FROM jobs WHERE status='pending' ORDER BY id LIMIT %s FOR UPDATE SKIP LOCKED", (n,))
        ids = [r[0] for r in c.fetchall()]
        if ids:
            c.execute(f"UPDATE jobs SET status='processing', locked_by=%s, locked_until=NOW(6) + INTERVAL 30 SECOND "
                      f"WHERE id IN ({','.join(['%s'] * len(ids))})", (worker, *ids))
    return ids


w1, w2 = conn(), conn()
got1 = claim(w1, "w1")          # w1 的事务还没提交，这两行仍被它锁着
got2 = claim(w2, "w2")
out("claim.skip_locked", f"两个消费者同时认领 2 条：w1 得到 {got1}，w2 跳过被锁的行得到 {got2}；另一个事务插入后未提交的 id={late_id}，两者都没有认领到")
w1.commit(); w2.commit()
late.commit()
w3 = conn(); got3 = claim(w3, "w3"); w3.commit()
out("claim.late", f"晚提交的任务提交之后，下一次按状态认领得到 {got3}：认领看的是状态，不是「上次读到哪」")

# 4. 租约：消费者认领后崩溃
with admin.cursor() as c:
    admin.commit()
    c.execute("UPDATE jobs SET locked_until = NOW(6) - INTERVAL 1 SECOND WHERE locked_by='w1'")   # 模拟 w1 崩溃且租约已过期
admin.commit()


def reclaim(cx, worker):
    with cx.cursor() as c:
        c.execute("SELECT id FROM jobs WHERE status='processing' AND locked_until < NOW(6) ORDER BY id FOR UPDATE SKIP LOCKED")
        ids = [r[0] for r in c.fetchall()]
        if ids:
            c.execute(f"UPDATE jobs SET locked_by=%s, locked_until=NOW(6) + INTERVAL 30 SECOND WHERE id IN ({','.join(['%s'] * len(ids))})",
                      (worker, *ids))
    cx.commit()
    return ids


r = reclaim(conn(), "w4")
out("lease.reclaim", f"w1 认领后崩溃、租约过期：w4 重新认领到 {r}；w2 的租约未过期，不会被抢走")


def finish(cx, worker, job):
    with cx.cursor() as c:
        n = c.execute("UPDATE jobs SET status='done' WHERE id=%s AND locked_by=%s AND status='processing'", (job, worker))
    cx.commit()
    return n


out("lease.stale", f"w1 恢复后想把 id={got1[0]} 标记完成（带 locked_by 条件）：影响 {finish(conn(), 'w1', got1[0])} 行；w4 标记完成：影响 {finish(conn(), 'w4', got1[0])} 行")
