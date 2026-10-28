#!/usr/bin/env python3
"""检查已跟踪的 shell 脚本在 macOS 自带 bash 3.2 下的两个陷阱。
1. UTF-8 locale 下，bash 3.2 把紧跟在 $NAME 后面的多字节字符当成变量名的一部分，set -u 时直接报 unbound variable；
   更糟的是 bash 3.2 在 set -e 下因展开错误退出时，EXIT trap 看到的 $? 是 0，只要设了 EXIT trap 脚本就以 0 退出，
   trap 里保存 $? 也救不回来。变量后面紧跟非 ASCII 字符时必须写成 ${NAME}。注释和带引号的 heredoc 正文不展开，不检查。
2. EXIT trap 统一先保存 $? 再原样退出：trap 'rc=$?; <清理> || true; exit $rc' EXIT，清理命令失败不改变脚本的退出状态。
用法：python3 shared/scripts/check-shell-portability.py [--fix]（--fix 只自动改写第 1 类）"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VAR = re.compile(r"\$([A-Za-z_][A-Za-z0-9_]*)(?=[^\x00-\x7F])")
QUOTED_HEREDOC = re.compile(r"<<(-?)\s*(['\"])([A-Za-z_][A-Za-z0-9_]*)\2")
EXIT_TRAP = re.compile(r"^\s*trap\s+(.+?)\s+EXIT\b")


def check(path: Path, fix: bool) -> list[str]:
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    errors, changed, heredoc = [], False, None
    for i, line in enumerate(lines):
        if heredoc:
            strip_tabs, end = heredoc
            if (line.lstrip("\t") if strip_tabs else line).rstrip("\n") == end:
                heredoc = None
            continue
        m = QUOTED_HEREDOC.search(line)
        if m:
            heredoc = (m.group(1) == "-", m.group(3))
        if line.lstrip().startswith("#"):
            continue
        if VAR.search(line):
            if fix:
                lines[i], changed = VAR.sub(r"${\1}", line), True
            else:
                names = ", ".join(sorted({v.group(0) for v in VAR.finditer(line)}))
                errors.append(f"{i + 1}: {names} 后紧跟非 ASCII 字符，应写成 ${{NAME}}")
        t = EXIT_TRAP.match(line)
        if t and not ("rc=$?" in t.group(1) and "exit $rc" in t.group(1)):
            errors.append(f"{i + 1}: EXIT trap 没有保留脚本的退出状态，应写成 trap 'rc=$?; ...; exit $rc' EXIT")
    if changed:
        path.write_text("".join(lines), encoding="utf-8")
    return [f"{path.relative_to(ROOT).as_posix()}:{e}" for e in errors]


fix = "--fix" in sys.argv[1:]
files = subprocess.check_output(["git", "ls-files", "*.sh"], cwd=ROOT, text=True).split()
errors = [e for f in files for e in check(ROOT / f, fix)]
for e in errors:
    print("错误：" + e)
print(f"检查了 {len(files)} 个 shell 脚本的 bash 3.2 兼容性，{len(errors)} 个问题")
sys.exit(1 if errors else 0)
