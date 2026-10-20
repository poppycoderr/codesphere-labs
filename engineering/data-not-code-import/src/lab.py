"""让模型产出数据而不是代码：同一份「生成的」CSV，比较逐个逗号切分 + 逐行提交 + 吞异常的导入脚本，
与标准 csv 解析 + Schema 校验 + 单事务导入的固定程序。输出 TSV（键、说明）。"""
import csv
import io
import sqlite3

# 模拟模型生成的数据：带 BOM 的 UTF-8；有一个带逗号的引号字段、一个带换行的字段、一个非法枚举、一个重复 ID、一个不存在的部门
GENERATED = (
    "﻿id,name,dept,status,note\r\n"
    "1,Ada,eng,active,\"负责 A,B 两个模块\"\r\n"
    "2,Bo,eng,active,\"第一行\n第二行\"\r\n"
    "3,Cy,ops,on_leave,\r\n"
    "4,Di,eng,P,把 P 写成了状态\r\n"
    "3,Ed,eng,active,重复的 id\r\n"
    "5,Fu,sales,active,部门不存在\r\n"
)
STATUS = {"active", "on_leave", "left"}
DEPTS = {"eng", "ops"}


def fresh_db():
    c = sqlite3.connect(":memory:", isolation_level=None)
    c.execute("create table dept(code text primary key)")
    c.executemany("insert into dept values(?)", [(d,) for d in DEPTS])
    c.execute("create table emp(id integer primary key, name text not null, dept text not null references dept(code), status text not null, note text)")
    c.execute("pragma foreign_keys=on")
    return c


def naive_import(text):
    """常见的生成脚本：按行切、按逗号切、不跳表头、每行单独提交、异常吞掉。"""
    c = fresh_db(); ok = 0; errors = 0
    for line in text.splitlines():
        cols = line.split(",")
        try:
            c.execute("insert into emp values(?,?,?,?,?)", (int(cols[0]), cols[1], cols[2], cols[3], cols[4] if len(cols) > 4 else None))
            ok += 1
        except Exception:
            errors += 1
    rows = c.execute("select id, name, status, note from emp order by id").fetchall()
    return ok, errors, rows


def validate(rows):
    """Schema 校验：枚举、引用、唯一、必填；返回 (合格行, 错误列表)，错误带行号便于预览"""
    seen, good, problems = set(), [], []
    for n, r in enumerate(rows, start=2):
        if r["status"] not in STATUS:
            problems.append(f"第 {n} 行 status={r['status']!r} 不在 {sorted(STATUS)}")
        elif r["dept"] not in DEPTS:
            problems.append(f"第 {n} 行 dept={r['dept']!r} 不存在")
        elif r["id"] in seen:
            problems.append(f"第 {n} 行 id={r['id']} 重复")
        else:
            seen.add(r["id"]); good.append(r)
    return good, problems


def fixed_import(text, rows_to_insert):
    """固定程序：单事务，任何一行失败整体回滚。"""
    c = fresh_db()
    try:
        c.execute("begin")
        for r in rows_to_insert:
            c.execute("insert into emp values(?,?,?,?,?)", (int(r["id"]), r["name"], r["dept"], r["status"], r["note"] or None))
        c.execute("commit")
        return "已提交", c.execute("select count(*) from emp").fetchone()[0]
    except sqlite3.Error as e:
        c.execute("rollback")
        return f"回滚（{type(e).__name__}: {e}）", c.execute("select count(*) from emp").fetchone()[0]


def out(k, v):
    print(f"{k}\t{v}", flush=True)


def main():
    ok, errors, rows = naive_import(GENERATED)
    out("naive.summary", f"逗号切分 + 逐行提交 + 吞异常：报告成功 {ok} 行、失败 {errors} 行，脚本没有报错退出")
    out("naive.rows", "库里实际的行：" + "；".join(f"{i}|{n}|{st}|{note}" for i, n, st, note in rows))

    plain = list(csv.DictReader(io.StringIO(GENERATED.encode("utf-8").decode("utf-8"), newline="")))
    out("bom.utf8", f"按 utf-8 解码后用 DictReader：第一列的列名是 {list(plain[0].keys())[0]!r}，取 row['id'] 得到 {plain[0].get('id')!r}")
    rows = list(csv.DictReader(io.StringIO(GENERATED.encode("utf-8").decode("utf-8-sig"), newline="")))
    out("bom.sig", f"按 utf-8-sig 解码：列名 {list(rows[0].keys())}")
    out("csv.quoted", f"标准 csv 解析：共 {len(rows)} 条记录；第 1 条 note={rows[0]['note']!r}，第 2 条 note={rows[1]['note']!r}")

    good, problems = validate(rows)
    out("validate", f"Schema 校验：{len(good)} 条合格，{len(problems)} 个问题：" + "；".join(problems))
    out("atomic.all", "不过滤、整批单事务导入：%s，库里 %d 行" % fixed_import(GENERATED, rows))
    out("atomic.valid", "只导入合格记录（导入前展示问题清单）：%s，库里 %d 行" % fixed_import(GENERATED, good))


main()
