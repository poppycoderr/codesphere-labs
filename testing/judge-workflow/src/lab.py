"""判题的一次结果对哪一组输入有效：候选代码与判据各有内容摘要，人工确认与执行结果都绑定这两个摘要；
执行在资源限制下进行，正常退出之后还要比较输出。所有候选都是自己写的可信夹具，不是不可信代码的隔离沙箱。
输出 TSV（键、说明）。"""
import hashlib
import json
import os
import resource
import signal
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path

CASES = [("2 3\n", "5\n"), ("-8 3\n", "-5\n"), ("0 0\n", "0\n")]      # 判据：版本化的输入与预期输出
FIXTURES = {
    "正确求和": "a, b = map(int, input().split()); print(a + b)",
    "写成减法": "a, b = map(int, input().split()); print(a - b)",
    "语法错误": "def broken(",
    "运行时异常": 'raise RuntimeError("fixture")',
    "长时间休眠": "import time; time.sleep(10)",
    "忙循环": "while True: pass",
    "申请 256 MiB": "x = bytearray(256 * 1024 * 1024)",
    "大量输出": 'print("x" * 100000)',
}
OUTPUT_LIMIT = 8192
WALL_SECONDS = 2.5


def out(key, text):
    print(f"{key}\t{text}", flush=True)


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()[:12]


def limits(cpu_hard):
    """在子进程 exec 之前设置：CPU 软限制 1 秒、地址空间 128 MiB、单个文件最多比上限多 1 字节、不写 core、最多 32 个文件描述符。"""
    return lambda: _apply(cpu_hard)


def _apply(cpu_hard):
    resource.setrlimit(resource.RLIMIT_CPU, (1, cpu_hard))
    resource.setrlimit(resource.RLIMIT_AS, (128 * 1024 * 1024,) * 2)
    resource.setrlimit(resource.RLIMIT_FSIZE, (OUTPUT_LIMIT + 1,) * 2)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_NOFILE, (32, 32))


def execute(code, stdin, cpu_hard=2):
    """运行一个候选：输出直接写进受大小限制的文件，而不是先收进内存再截断；墙钟超时后杀掉整个进程组。"""
    try:
        compile(code, "candidate.py", "exec")
    except SyntaxError:
        return {"status": "编译检查拒绝"}
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "candidate.py").write_text(code)
        with (root / "stdout").open("wb") as so, (root / "stderr").open("wb") as se:
            proc = subprocess.Popen([sys.executable, "-I", "candidate.py"], stdin=subprocess.PIPE, stdout=so, stderr=se,
                                    cwd=tmp, env={"LANG": "C.UTF-8"}, start_new_session=True, preexec_fn=limits(cpu_hard))
            try:
                proc.communicate(stdin.encode(), timeout=WALL_SECONDS)
                timed_out = False
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.communicate()
                timed_out = True
        stdout, stderr = (root / "stdout").read_bytes(), (root / "stderr").read_bytes()
    if timed_out:
        status = "墙钟超时"
    elif len(stdout) > OUTPUT_LIMIT or len(stderr) > OUTPUT_LIMIT:
        status = "输出超限"
    elif b"MemoryError" in stderr:
        status = "内存申请失败"
    elif proc.returncode < 0:
        status = f"被信号 {signal.Signals(-proc.returncode).name} 终止"
    elif proc.returncode:
        status = "非零退出"
    else:
        status = "正常退出"
    return {"status": status, "returncode": proc.returncode, "stdout": stdout.decode(errors="replace"), "stdout_bytes": len(stdout)}


def judge(code):
    """逐组比较输出：只有正常退出且输出与预期完全一致才算通过。"""
    results = [execute(code, given) for given, _ in CASES]
    passed = sum(r["status"] == "正常退出" and r["stdout"] == want for r, (_, want) in zip(results, CASES))
    return passed


out("env", f"python={sys.version.split()[0]}")

# 一、执行：每种失败落在哪个限制上
for name, code in FIXTURES.items():
    r = execute(code, CASES[0][0])
    detail = "" if "returncode" not in r else f"，退出码 {r['returncode']}，标准输出 {r['stdout_bytes']} 字节"
    out(f"exec.{name}", f"{name}：{r['status']}{detail}")
r = execute(FIXTURES["忙循环"], CASES[0][0], cpu_hard=1)
out("exec.cpu_equal", f"忙循环、CPU 软硬限制都设为 1 秒：{r['status']}，退出码 {r['returncode']}（与墙钟超时被杀的退出码相同）")
out("judge.correct", f"正确求和：{judge(FIXTURES['正确求和'])} / {len(CASES)} 组通过")
out("judge.wrong", f"写成减法：{judge(FIXTURES['写成减法'])} / {len(CASES)} 组通过（每次都正常退出，错在输出）")

# 二、结果绑定：确认与结果都带着候选摘要和判据摘要
db = sqlite3.connect(":memory:")
db.executescript("""
CREATE TABLE review (id TEXT PRIMARY KEY, candidate TEXT NOT NULL, oracle TEXT NOT NULL);
CREATE TABLE result (id TEXT PRIMARY KEY, review TEXT NOT NULL, candidate TEXT NOT NULL, oracle TEXT NOT NULL, verdict TEXT NOT NULL);
""")
code_hash, oracle_hash = digest(FIXTURES["正确求和"]), digest(json.dumps(CASES))
db.execute("INSERT INTO review VALUES ('rv1', ?, ?)", (code_hash, oracle_hash))


def accept(result_id, candidate, oracle, verdict):
    """接受一个执行结果：候选和判据都必须是人工确认时的那一份；同一结果 ID 只接受一次，换内容重发会被拒绝。"""
    reviewed = db.execute("SELECT candidate, oracle FROM review WHERE id = 'rv1'").fetchone()
    if reviewed != (candidate, oracle):
        changed = "候选代码" if reviewed[0] != candidate else "判据"
        return f"拒绝：{changed}与确认时不同，确认已失效"
    prior = db.execute("SELECT candidate, oracle, verdict FROM result WHERE id = ?", (result_id,)).fetchone()
    if prior:
        return "重复提交，保留原结果" if prior == (candidate, oracle, verdict) else "拒绝：同一结果 ID 换了内容"
    db.execute("INSERT INTO result VALUES (?, 'rv1', ?, ?, ?)", (result_id, candidate, oracle, verdict))
    return "已接受"


out("bind.accept", f"按确认时的候选与判据提交结果 res1：{accept('res1', code_hash, oracle_hash, 'pass')}")
out("bind.duplicate", f"res1 原样重发：{accept('res1', code_hash, oracle_hash, 'pass')}；换成 fail 重发：{accept('res1', code_hash, oracle_hash, 'fail')}")
out("bind.candidate_changed", f"候选代码改过之后提交 res2：{accept('res2', digest(FIXTURES['写成减法']), oracle_hash, 'pass')}")
out("bind.oracle_changed", f"判据改过之后提交 res3：{accept('res3', code_hash, digest('new-oracle'), 'pass')}")
