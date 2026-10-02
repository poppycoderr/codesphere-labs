"""失败路径上的几种写法。由 scripts/verify.sh 分别以普通模式和 -O 模式运行。"""
import os
import subprocess
import sys
import tempfile
import warnings
from concurrent.futures import ThreadPoolExecutor


def out(key, fact):
    print(f"{key}\t{fact}")


def withdraw(balance, amount):
    assert amount > 0, "amount must be positive"          # 用 assert 校验外部输入
    return balance - amount


def withdraw_checked(balance, amount):
    if amount <= 0:
        raise ValueError("amount must be positive")
    return balance - amount


def attempt(fn):
    try:
        return f"返回 {fn()!r}"
    except BaseException as e:
        return f"抛出 {type(e).__name__}"


def main():
    mode = "-O" if sys.flags.optimize else "普通"
    out(f"env.{mode}", f"python={sys.version.split()[0]}，sys.flags.optimize={sys.flags.optimize}，__debug__={__debug__}")

    # 1. assert 在 -O 下被整条去掉
    out(f"assert.{mode}", f"withdraw(100, -50)，校验写成 assert：{attempt(lambda: withdraw(100, -50))}")
    out(f"raise.{mode}", f"校验写成 if … raise ValueError：{attempt(lambda: withdraw_checked(100, -50))}")
    if sys.flags.optimize:
        return

    # 2. assert 后面加括号和逗号：断言的是一个非空元组
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        code = compile("assert (1 > 2, 'never fails')", "<demo>", "exec")
        exec(code)
    out("assert.tuple", f"assert (1 > 2, 'msg') 执行通过；编译时的警告：{caught[0].category.__name__ if caught else '无'}")

    # 3. finally 里的 return 会吞掉异常
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        ns = {}
        exec(compile("def f():\n    try:\n        raise RuntimeError('lost')\n    finally:\n        return 'ok'\n", "<demo>", "exec"), ns)
    out("finally.return", f"try 里抛异常、finally 里 return 'ok'：调用 {attempt(ns['f'])}；编译时的警告：{caught[0].category.__name__ if caught else '无'}")

    # 4. 裸 except 连退出也拦住
    def bare():
        try:
            sys.exit(3)
        except:                                            # noqa: E722
            return "被裸 except 拦住，进程没有退出"

    def narrow():
        try:
            sys.exit(3)
        except Exception:
            return "被拦住"

    out("except.bare", f"try 里 sys.exit(3)，用裸 except：{attempt(bare)}；用 except Exception：{attempt(narrow)}")

    # 5. with 保证关闭文件，不保证内容完整
    d = tempfile.mkdtemp()
    path = os.path.join(d, "config.json")
    with open(path, "w", encoding="utf-8") as f:
        f.write('{"version": 1, "limit": 100}')

    def rewrite_in_place():
        with open(path, "w", encoding="utf-8") as f:
            f.write('{"version": 2, ')
            raise RuntimeError("serialization failed halfway")

    r = attempt(rewrite_in_place)
    out("write.in_place", f"原地重写配置文件、写到一半出错：{r}；文件现在是 {open(path, encoding='utf-8').read()!r}")

    with open(path, "w", encoding="utf-8") as f:
        f.write('{"version": 1, "limit": 100}')

    def write_atomic(content, fail_halfway):
        tmp = path + ".tmp"
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                f.write(content[: len(content) // 2])
                if fail_halfway:
                    raise RuntimeError("serialization failed halfway")
                f.write(content[len(content) // 2:])
                f.flush()
                os.fsync(f.fileno())                           # 内容落盘之后再替换
            os.replace(tmp, path)                              # 同一个文件系统内的原子替换
        finally:
            if os.path.exists(tmp):
                os.remove(tmp)

    r = attempt(lambda: write_atomic('{"version": 2, "limit": 200}', True))
    out("write.atomic", f"先写临时文件再 os.replace、写到一半出错：{r}；文件现在是 {open(path, encoding='utf-8').read()!r}，残留临时文件 {os.path.exists(path + '.tmp')}")
    write_atomic('{"version": 2, "limit": 200}', False)
    out("write.atomic_ok", f"同一个函数正常写完：文件现在是 {open(path, encoding='utf-8').read()!r}")

    # 6. 子进程失败不会自动变成异常
    fail = [sys.executable, "-c", "import sys; sys.exit(2)"]
    p = subprocess.run(fail)
    out("subprocess.unchecked", f"subprocess.run(失败的命令)：没有异常，returncode={p.returncode}")
    out("subprocess.checked", f"加上 check=True：{attempt(lambda: subprocess.run(fail, check=True))}")

    # 7. 线程池里的异常要取结果才看得到
    def boom(x):
        raise ValueError(f"bad item {x}")

    with ThreadPoolExecutor(max_workers=2) as pool:
        future = pool.submit(boom, 1)
        lazy = pool.map(boom, [1, 2])
    out("pool.submit", f"submit 之后不调用 result()：离开 with 时没有异常；future.exception() 是 {type(future.exception()).__name__}")
    out("pool.map", f"map 之后不遍历结果：没有异常；开始遍历时 {attempt(lambda: list(lazy))}")


if __name__ == "__main__":
    main()
