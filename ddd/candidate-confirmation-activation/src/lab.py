"""候选、确认与生效：抽取出来的金额要经过确认才进入正式记录，训练出来的模型要经过验证才能被部署指针引用。
两条路径用同一组约束：候选绑定来源的内容哈希，确认与切换都按版本条件更新，命令带幂等回执，异步回调匹配尝试号。
SQLite 单连接、顺序执行。输出 TSV（键、说明）。"""
import hashlib
import json
import sqlite3
import tempfile
from pathlib import Path


class Rejected(Exception):
    """领域规则拒绝了这条命令。"""


def out(key, text):
    print(f"{key}\t{text}", flush=True)


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()[:12]


SCHEMA = """
CREATE TABLE document (id TEXT PRIMARY KEY, content_hash TEXT NOT NULL, revision INTEGER NOT NULL);
CREATE TABLE candidate (id TEXT PRIMARY KEY, document TEXT NOT NULL, source_hash TEXT NOT NULL,
                        amount INTEGER NOT NULL, state TEXT NOT NULL);
CREATE TABLE ledger (document TEXT PRIMARY KEY, amount INTEGER NOT NULL, version INTEGER NOT NULL);
CREATE TABLE receipt (request_id TEXT PRIMARY KEY, payload TEXT NOT NULL);
CREATE TABLE extraction_job (id TEXT PRIMARY KEY, attempt INTEGER NOT NULL, state TEXT NOT NULL);
CREATE TABLE model_version (id TEXT PRIMARY KEY, artifact_hash TEXT NOT NULL, state TEXT NOT NULL);
CREATE TABLE deployment (target TEXT PRIMARY KEY, model TEXT NOT NULL, version INTEGER NOT NULL);
"""


def confirm(db, request_id, candidate_id, expected_version):
    """确认一个候选：回执去重、候选仍有效、来源未变、金额合法，再按业务版本条件写入正式记录。"""
    payload = json.dumps([candidate_id, expected_version])
    with db:
        prior = db.execute("SELECT payload FROM receipt WHERE request_id = ?", (request_id,)).fetchone()
        if prior:
            if prior[0] != payload:
                raise Rejected("同一个请求 ID 换了参数")
            return "重复请求，返回原结果"
        doc, source_hash, amount, state = db.execute(
            "SELECT document, source_hash, amount, state FROM candidate WHERE id = ?", (candidate_id,)).fetchone()
        current_hash = db.execute("SELECT content_hash FROM document WHERE id = ?", (doc,)).fetchone()[0]
        if state != "pending":
            raise Rejected(f"候选状态是 {state}")
        if current_hash != source_hash:
            raise Rejected("候选引用的来源已经变了")
        if amount < 0:
            raise Rejected("金额不能为负")
        changed = db.execute("UPDATE ledger SET amount = ?, version = version + 1 WHERE document = ? AND version = ?",
                             (amount, doc, expected_version)).rowcount
        if changed != 1:
            raise Rejected("业务版本已变化")
        db.execute("UPDATE candidate SET state = 'confirmed' WHERE id = ?", (candidate_id,))
        db.execute("INSERT INTO receipt VALUES (?, ?)", (request_id, payload))
        return "已确认"


def finish_attempt(db, job_id, attempt):
    """抽取任务的回调：只有当前尝试、且仍在运行时才能改状态。"""
    with db:
        return db.execute("UPDATE extraction_job SET state = 'succeeded' WHERE id = ? AND attempt = ? AND state = 'running'",
                          (job_id, attempt)).rowcount


def activate(db, target, model_id, expected_version):
    """把部署指针切到一个已验证的模型版本；回退也是一次新的切换。"""
    with db:
        row = db.execute("SELECT state FROM model_version WHERE id = ?", (model_id,)).fetchone()
        if row is None or row[0] != "validated":
            raise Rejected(f"{model_id} 未通过验证")
        changed = db.execute("UPDATE deployment SET model = ?, version = version + 1 WHERE target = ? AND version = ?",
                             (model_id, target, expected_version)).rowcount
        if changed != 1:
            raise Rejected("部署版本已变化")
    return "已切换"


def attempt(fn, *args):
    try:
        return fn(*args)
    except Rejected as e:
        return f"拒绝：{e}"


def ledger(db):
    amount, version = db.execute("SELECT amount, version FROM ledger WHERE document = 'doc1'").fetchone()
    return f"金额 {amount}、版本 {version}"


def pointer(db):
    model, version = db.execute("SELECT model, version FROM deployment WHERE target = 'api'").fetchone()
    return f"指向 {model}、版本 {version}"


with tempfile.TemporaryDirectory() as tmp:
    path = Path(tmp) / "desk.db"
    db = sqlite3.connect(path)
    db.executescript(SCHEMA)
    with db:
        db.execute("INSERT INTO document VALUES ('doc1', ?, 1)", (digest("amount=100"),))
        db.execute("INSERT INTO ledger VALUES ('doc1', 0, 0)")
        db.execute("INSERT INTO candidate VALUES ('c1', 'doc1', ?, 100, 'pending')", (digest("amount=100"),))
    out("env", f"sqlite={sqlite3.sqlite_version}")

    # 一、抽取结果
    out("extract.pending", f"抽取出候选 c1（金额 100）之后，正式记录：{ledger(db)}")
    out("extract.confirm", f"请求 r1 按版本 0 确认 c1：{attempt(confirm, db, 'r1', 'c1', 0)}；正式记录：{ledger(db)}")
    out("extract.duplicate", f"r1 原样重发：{attempt(confirm, db, 'r1', 'c1', 0)}；正式记录：{ledger(db)}")
    out("extract.reused_key", f"r1 换了参数（版本 1）再发：{attempt(confirm, db, 'r1', 'c1', 1)}")
    with db:
        db.execute("INSERT INTO candidate VALUES ('c2', 'doc1', ?, 999, 'pending')", (digest("amount=100"),))
    out("extract.stale_version", f"另一位审核人基于版本 0 确认候选 c2（金额 999）：{attempt(confirm, db, 'r2', 'c2', 0)}")
    with db:
        db.execute("UPDATE document SET content_hash = ?, revision = 2 WHERE id = 'doc1'", (digest("amount=200"),))
    out("extract.stale_source", f"文档更新为第 2 版后，按最新版本 1 确认 c2：{attempt(confirm, db, 'r3', 'c2', 1)}；正式记录：{ledger(db)}")
    with db:
        db.execute("INSERT INTO candidate VALUES ('c3', 'doc1', ?, 200, 'pending')", (digest("amount=200"),))
    out("extract.reextract", f"对第 2 版重新抽取出 c3（金额 200），按版本 1 确认：{attempt(confirm, db, 'r4', 'c3', 1)}；正式记录：{ledger(db)}")
    with db:
        db.execute("INSERT INTO extraction_job VALUES ('job1', 2, 'running')")
    rows = [finish_attempt(db, "job1", 1), finish_attempt(db, "job1", 2), finish_attempt(db, "job1", 2)]
    out("extract.callback", f"抽取任务已是第 2 次尝试：第 1 次尝试的迟到回调更新 {rows[0]} 行，第 2 次的回调更新 {rows[1]} 行，同一回调重复到达更新 {rows[2]} 行")

    # 二、模型发布
    with db:
        db.execute("INSERT INTO model_version VALUES ('m1', ?, 'validated')", (digest("weights-v1"),))
        db.execute("INSERT INTO model_version VALUES ('m2', ?, 'candidate')", (digest("weights-v2"),))
        db.execute("INSERT INTO deployment VALUES ('api', 'm1', 0)")
    out("release.candidate", f"训练出 m2（状态 candidate），切换到 m2：{attempt(activate, db, 'api', 'm2', 0)}；{pointer(db)}")
    with db:
        db.execute("UPDATE model_version SET state = 'validated' WHERE id = 'm2'")
    out("release.activate", f"m2 通过验证后按版本 0 切换：{attempt(activate, db, 'api', 'm2', 0)}；{pointer(db)}")
    out("release.stale", f"另一个发布请求仍基于版本 0 要切回 m1：{attempt(activate, db, 'api', 'm1', 0)}；{pointer(db)}")
    out("release.rollback", f"按版本 1 回退到 m1：{attempt(activate, db, 'api', 'm1', 1)}；{pointer(db)}（回退也是一次新版本，不是把版本减回去）")
    db.close()
    db = sqlite3.connect(path)
    out("release.reopen", f"关闭连接后重新打开：{pointer(db)}；{ledger(db)}")
    db.close()
