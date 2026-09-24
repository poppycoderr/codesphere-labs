#!/usr/bin/env python3
"""把 surefire 的 XML 报告整理成「结果、类名、用例名」列表，不保留报告中的系统属性。
用法：surefire-summary.py <surefire-reports 目录> <输出文件>"""
import glob
import sys
import xml.etree.ElementTree as ET

rows, total, bad = [], 0, 0
for f in sorted(glob.glob(sys.argv[1] + "/TEST-*.xml")):
    for tc in ET.parse(f).getroot().iter("testcase"):
        total += 1
        failed = tc.find("failure") is not None or tc.find("error") is not None
        bad += failed
        rows.append(f"{'FAIL' if failed else 'PASS'}  {tc.get('classname').split('.')[-1]}  {tc.get('name')}")
with open(sys.argv[2], "w", encoding="utf-8") as out:
    out.write("# 由 shared/scripts/surefire-summary.py 从 surefire-reports/TEST-*.xml 生成\n")
    out.write("\n".join(rows) + f"\n\ntests={total} failures={bad}\n")
