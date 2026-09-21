#!/usr/bin/env python3
"""基础检查：每个实验目录都有最低要求的文件，experiment.yaml 字段完整且取值合法。
实验目录的判定：包含 experiment.yaml 的目录。用法：python3 shared/scripts/check-structure.py"""
import sys
from pathlib import Path
import yaml

ROOT = Path(__file__).resolve().parents[2]
REQUIRED_FILES = ["README.md", "experiment.yaml", "VERIFICATION.md", "Makefile", "evidence/README.md"]
REQUIRED_KEYS = ["id", "title", "status", "tier", "article", "runtime", "commands", "evidence", "last_verified"]
STATUS = {"planned", "verified", "partial", "stale", "archived"}
TIERS = {"regular", "regular-docker", "performance"}

errors, experiments = [], sorted(p.parent for p in ROOT.glob("*/*/experiment.yaml"))
for d in experiments:
    rel = d.relative_to(ROOT).as_posix()
    for f in REQUIRED_FILES:
        if not (d / f).exists():
            errors.append(f"{rel}: 缺少 {f}")
    if not (d / "scripts" / "verify.sh").exists():
        errors.append(f"{rel}: 缺少 scripts/verify.sh")
    meta = yaml.safe_load((d / "experiment.yaml").read_text(encoding="utf-8"))
    for k in REQUIRED_KEYS:
        if k not in meta:
            errors.append(f"{rel}: experiment.yaml 缺少 {k}")
    if meta.get("id") != rel:
        errors.append(f"{rel}: id 应为 {rel}，实际 {meta.get('id')}")
    if meta.get("status") not in STATUS:
        errors.append(f"{rel}: status 取值非法：{meta.get('status')}")
    if meta.get("tier") not in TIERS:
        errors.append(f"{rel}: tier 取值非法：{meta.get('tier')}")
    if meta.get("status") == "verified" and not any((d / "evidence").iterdir()):
        errors.append(f"{rel}: 标记为 verified 但 evidence/ 为空")
    art = meta.get("article", {})
    if art.get("repository") != "codesphere" or not str(art.get("path", "")).startswith("docs/"):
        errors.append(f"{rel}: article 应指向 codesphere 仓库 docs/ 下的文章")

for e in errors:
    print("错误：" + e)
print(f"检查了 {len(experiments)} 个实验，{len(errors)} 个问题")
sys.exit(1 if errors else 0)
