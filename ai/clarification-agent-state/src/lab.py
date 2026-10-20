"""等待人工澄清的代理：把「提问—等待—收到回答—继续」建模为持久状态，对比让模型自问自答的做法。

模型是确定性的假实现：根据已确认的答案决定还缺哪个问题，缺口补齐后给出验收草案。状态存在 SQLite 里，
用两个独立连接模拟进程重启。输出 TSV（键、说明）。
"""
import json
import sqlite3
import tempfile
import time
from pathlib import Path

QUESTIONS = {
    "q-late": "逾期提交的作业怎样处理？",
    "q-group": "小组作业由谁提交？",
}


def fake_model(answers: dict) -> dict:
    """缺哪个问题就问哪个；问题 ID 稳定，不随措辞变化。"""
    for qid, text in QUESTIONS.items():
        if qid not in answers:
            return {"type": "need_input", "question_id": qid, "text": text}
    return {"type": "draft", "criteria": [f"{qid}: {a}" for qid, a in sorted(answers.items())]}


def self_answering_agent() -> list:
    """反例：模型提出问题后由同一个模型补一个「最可能」的答案，然后直接写草案。"""
    answers, invented = {}, []
    while True:
        step = fake_model(answers)
        if step["type"] == "draft":
            return step["criteria"], invented
        guess = "按惯例处理" if step["question_id"] == "q-late" else "组长提交"
        answers[step["question_id"]] = guess
        invented.append(step["question_id"])


SCHEMA = """
create table if not exists task(id text primary key, state text, answers text, pending text, asked int, deadline real, draft text);
create table if not exists inbox(answer_id text primary key, task_id text, question_id text, value text);
"""


class Agent:
    """状态机：drafting → awaiting_input → drafting → done；awaiting_input 可以 expired 或 cancelled。"""

    def __init__(self, db: Path, max_questions=3, wait_seconds=60.0):
        self.c = sqlite3.connect(db, isolation_level=None)
        self.c.executescript(SCHEMA)
        self.max_questions, self.wait_seconds = max_questions, wait_seconds

    def start(self, task_id, now=None):
        self.c.execute("insert into task values(?, 'drafting', '{}', null, 0, null, null)", (task_id,))
        return self.step(task_id, now)

    def row(self, task_id):
        state, answers, pending, asked, deadline, draft = self.c.execute(
            "select state, answers, pending, asked, deadline, draft from task where id=?", (task_id,)).fetchone()
        return state, json.loads(answers), pending, asked, deadline, draft

    def step(self, task_id, now=None):
        now = time.time() if now is None else now
        state, answers, pending, asked, deadline, draft = self.row(task_id)
        if state != "drafting":
            return state
        out = fake_model(answers)
        if out["type"] == "draft":
            self.c.execute("update task set state='done', draft=? where id=?", (json.dumps(out["criteria"], ensure_ascii=False), task_id))
            return "done"
        if asked >= self.max_questions:
            self.c.execute("update task set state='needs_human_review' where id=?", (task_id,))
            return "needs_human_review"
        # 提问并停下：把等待写进持久状态，而不是在进程里阻塞
        self.c.execute("update task set state='awaiting_input', pending=?, asked=asked+1, deadline=? where id=?",
                       (out["question_id"], now + self.wait_seconds, task_id))
        return f"awaiting_input({out['question_id']})"

    def deliver(self, task_id, answer_id, question_id, value, now=None):
        """收到一条回答：按 answer_id 去重，只接受当前在等的问题，过期后不再接受。"""
        now = time.time() if now is None else now
        self.c.execute("begin immediate")
        try:
            if self.c.execute("select 1 from inbox where answer_id=?", (answer_id,)).fetchone():
                self.c.execute("commit")
                return "重复投递，忽略"
            state, answers, pending, asked, deadline, draft = self.row(task_id)
            if state != "awaiting_input":
                self.c.execute("commit")
                return f"拒绝：当前状态是 {state}"
            if question_id != pending:
                self.c.execute("commit")
                return f"拒绝：在等 {pending}，收到的是 {question_id}"
            if now > deadline:
                self.c.execute("update task set state='expired' where id=?", (task_id,))
                self.c.execute("commit")
                return "拒绝：已过期，任务转为 expired"
            answers[question_id] = value
            self.c.execute("insert into inbox values(?,?,?,?)", (answer_id, task_id, question_id, value))
            self.c.execute("update task set state='drafting', pending=null, answers=? where id=?",
                           (json.dumps(answers, ensure_ascii=False), task_id))
            self.c.execute("commit")
        except Exception:
            self.c.execute("rollback")
            raise
        return "接受，" + self.step(task_id, now)

    def cancel(self, task_id):
        n = self.c.execute("update task set state='cancelled' where id=? and state in ('drafting','awaiting_input')", (task_id,)).rowcount
        return "已取消" if n else "无法取消"


def out(k, v):
    print(f"{k}\t{v}", flush=True)


def main():
    criteria, invented = self_answering_agent()
    out("self_answer", f"模型自问自答：直接产出 {len(criteria)} 条验收条件，其中 {len(invented)} 条的依据是模型自己编的答案（{', '.join(invented)}）")

    db = Path(tempfile.mkdtemp()) / "agent.db"
    a = Agent(db)
    out("start", f"新任务：{a.start('t1')}")
    # 模拟进程重启：丢掉这个对象，用新连接恢复
    del a
    b = Agent(db)
    state, *_ = b.row("t1")
    out("restart", f"重启后读取持久状态：{state}，仍在等 {b.row('t1')[2]}，没有重新提问（已提问 {b.row('t1')[3]} 次）")
    out("wrong_question", "回答一个没有在问的问题：" + b.deliver("t1", "ans-0", "q-group", "教师统一收"))
    out("answer1", "回答 q-late：" + b.deliver("t1", "ans-1", "q-late", "超过 24 小时记为迟交，不影响继续提交"))
    out("duplicate", "同一条回答因网络重试再投递一次：" + b.deliver("t1", "ans-1", "q-late", "超过 24 小时记为迟交，不影响继续提交"))
    out("answer2", "回答 q-group：" + b.deliver("t1", "ans-2", "q-group", "组内任一成员提交，以最后一次为准"))
    out("draft", "草案依据的都是人给出的答案：" + "；".join(json.loads(b.row("t1")[5])))
    out("after_done", "任务完成后又收到一条回答：" + b.deliver("t1", "ans-3", "q-late", "改主意了"))

    c = Agent(db, wait_seconds=10)
    t0 = 1_000_000.0
    out("expire.start", "限时 10 秒的任务：" + c.start("t2", now=t0))
    out("expire.late", "第 11 秒才收到回答：" + c.deliver("t2", "ans-9", "q-late", "x", now=t0 + 11))

    d = Agent(db)
    d.start("t3")
    out("cancel", f"等待中取消：{d.cancel('t3')}；再投递回答：{d.deliver('t3', 'ans-10', 'q-late', 'x')}")

    e = Agent(db, max_questions=1)
    e.start("t4")
    out("budget", "只允许问 1 个问题：回答之后 " + e.deliver("t4", "ans-11", "q-late", "迟交扣分").split("，")[1] + "，不再继续追问")


main()
