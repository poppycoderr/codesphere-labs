#!/usr/bin/env python3
"""按层级列出实验 id，供 Makefile 与 CI 使用。用法：list-experiments.py [tier ...]"""
import sys
from pathlib import Path
import yaml

ROOT = Path(__file__).resolve().parents[2]
tiers = set(sys.argv[1:])
for p in sorted(ROOT.glob("*/*/experiment.yaml")):
    meta = yaml.safe_load(p.read_text(encoding="utf-8"))
    if not tiers or meta["tier"] in tiers:
        print(meta["id"])
