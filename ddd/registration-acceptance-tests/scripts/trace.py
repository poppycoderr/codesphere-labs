#!/usr/bin/env python3
"""规则表、词汇表与代码的可追踪检查。
用法：trace.py <test-results.txt> <输出文件>
- 规则表中的每条规则至少有一个用例名以其编号开头；每个用例名引用的编号都在规则表中；
- 词汇表中的每个代码名都在 src/main 中以类型或方法的形式出现。"""
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
rules = re.findall(r"^\| (R\d+) \| ([^|]+)\|", (root / "model/rules.md").read_text(encoding="utf-8"), re.M)
tests = re.findall(r"^(PASS|FAIL)  \S+  (R\d+) (.+)$", Path(sys.argv[1]).read_text(encoding="utf-8"), re.M)
src = "\n".join(p.read_text(encoding="utf-8") for p in (root / "src/main").rglob("*.java"))
terms = [(t, c) for t, c in re.findall(r"^\| ([^|]+) \|[^|]+\|[^|]+\| (\w+) \|$", (root / "model/glossary.md").read_text(encoding="utf-8"), re.M) if t.strip() != "术语"]

lines, problems = [], []
ids = [r for r, _ in rules]
for rid, text in rules:
    mine = [f"{res} {name}" for res, r, name in tests if r == rid]
    lines.append(f"{rid}\t{text.strip()}\t{len(mine)} 个用例\t{'；'.join(mine)}")
    if not mine:
        problems.append(f"{rid} 没有验收测试")
for res, r, name in tests:
    if r not in ids:
        problems.append(f"用例「{r} {name}」引用了规则表中没有的编号")
for term, code in terms:
    pattern = rf"\b(class|record|interface|enum) {code}\b|\b{code}\("
    found = re.search(pattern, src) is not None
    lines.append(f"术语\t{term.strip()}\t{code}\t{'找到' if found else '缺失'}")
    if not found:
        problems.append(f"术语「{term.strip()}」的代码名 {code} 不在 src/main 中")
lines.append(f"规则 {len(rules)} 条，用例 {len(tests)} 个，术语 {len(terms)} 个，问题 {len(problems)} 个")
lines += [f"问题\t{p}" for p in problems]
Path(sys.argv[2]).write_text("\n".join(lines) + "\n", encoding="utf-8")
