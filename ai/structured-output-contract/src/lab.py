"""模型输出的结构契约：JSON 语法、Schema、领域规则三层校验；Pydantic 宽松模式的类型转换与额外字段；「修复」会改变业务含义；重试预算。
模型的输出是写死的样本，不调用任何模型。输出 TSV（键、说明）。"""
import json
import re

import pydantic
from pydantic import BaseModel, ConfigDict, Field, ValidationError

CATALOG = {"A100": 1999, "B200": 4500}   # 权威价格（分），由应用持有，不由模型决定


class LineLax(BaseModel):
    sku: str
    qty: int
    price_cents: int


class LineStrict(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")
    sku: str = Field(pattern=r"^[A-Z]\d{3}$")
    qty: int = Field(gt=0, le=100)
    price_cents: int


def out(k, v):
    print(f"{k}\t{v}", flush=True)


def errors(e: ValidationError):
    return "；".join(f"{'.'.join(map(str, x['loc']))}: {x['type']}" for x in e.errors())


def try_model(model, data):
    try:
        return "通过：" + model.model_validate(data).model_dump_json()
    except ValidationError as e:
        return "拒绝：" + errors(e)


def parse_layer(raw):
    """第一层：从模型文本里得到 JSON 对象。只接受整段就是一个 JSON 对象。"""
    try:
        v = json.loads(raw)
    except json.JSONDecodeError as e:
        return None, f"JSON 语法错误：{e.msg}（第 {e.pos} 个字符）"
    if not isinstance(v, dict):
        return None, f"不是 JSON 对象，而是 {type(v).__name__}"
    return v, None


def domain_layer(line: LineStrict):
    """第三层：领域规则。价格以应用持有的目录为准；商品必须存在。"""
    if line.sku not in CATALOG:
        return f"未知商品 {line.sku}"
    if line.price_cents != CATALOG[line.sku]:
        return f"价格 {line.price_cents} 与目录价 {CATALOG[line.sku]} 不符"
    return None


def main():
    out("env", f"pydantic={pydantic.VERSION}")
    # 一、宽松模式与严格模式
    samples = {
        "str_number": {"sku": "A100", "qty": "3", "price_cents": 1999},
        "float_whole": {"sku": "A100", "qty": 3.0, "price_cents": 1999},
        "float_frac": {"sku": "A100", "qty": 3.5, "price_cents": 1999},
        "bool": {"sku": "A100", "qty": True, "price_cents": 1999},
        "extra": {"sku": "A100", "qty": 1, "price_cents": 1999, "discount_pct": 90},
        "negative": {"sku": "A100", "qty": -2, "price_cents": 1999},
    }
    label = {"str_number": "qty 是字符串 \"3\"", "float_whole": "qty 是 3.0", "float_frac": "qty 是 3.5", "bool": "qty 是 true",
             "extra": "多了一个 discount_pct=90", "negative": "qty 是 -2"}
    for k, d in samples.items():
        out(f"lax.{k}", f"{label[k]}，默认（宽松）模式：{try_model(LineLax, d)}")
        out(f"strict.{k}", f"{label[k]}，strict + extra=forbid + 范围：{try_model(LineStrict, d)}")

    # 二、语法层：模型常见的输出形态
    texts = {
        "fenced": '```json\n{"sku": "A100", "qty": 1, "price_cents": 1999}\n```',
        "prose": '好的，结果如下：{"sku": "A100", "qty": 1, "price_cents": 1999}',
        "truncated": '{"sku": "A100", "qty": 1, "price_c',
        "array": '[{"sku": "A100", "qty": 1, "price_cents": 1999}]',
    }
    for k, t in texts.items():
        v, err = parse_layer(t)
        out(f"parse.{k}", f"{ {'fenced': '包在代码块里', 'prose': '前面有一句话', 'truncated': '输出被截断', 'array': '返回了数组'}[k] }：{err or '通过'}")
    nested = '说明 {"note": "见 {附件}"} 结果 {"sku": "A100", "qty": 1, "price_cents": 1999}'
    first_brace = nested[nested.index("{"): nested.index("}") + 1]
    out("parse.first_brace", f"「取第一个左括号到第一个右括号」的启发式，从带嵌套说明的文本里取到：{first_brace!r}")

    # 三、领域层：结构正确不等于业务正确
    for k, d in {"ok": {"sku": "A100", "qty": 2, "price_cents": 1999},
                 "price": {"sku": "A100", "qty": 2, "price_cents": 1},
                 "unknown": {"sku": "Z999", "qty": 2, "price_cents": 1999}}.items():
        line = LineStrict.model_validate(d)
        out(f"domain.{k}", f"Schema 通过的 {json.dumps(d)}：领域校验 {domain_layer(line) or '通过'}")

    # 四、「修复」：让模型把不合法的输出改合法，可能顺手改了含义
    original = '{"sku": "B200", "qty": "两件", "price_cents": 4500}'
    repaired = '{"sku": "A100", "qty": 2, "price_cents": 1999}'   # 修复后的输出：语法、Schema、领域都合法，但商品变了
    v, _ = parse_layer(original)
    out("repair.original", "原始输出：" + try_model(LineStrict, v))
    rv, _ = parse_layer(repaired)
    out("repair.result", "修复后的输出：" + try_model(LineStrict, rv) + "；领域校验 " + (domain_layer(LineStrict.model_validate(rv)) or "通过"))
    must_keep = [f for f in ("sku",) if v[f] != rv[f]]
    out("repair.guard", f"对比修复前后应保持不变的字段：{must_keep or '无变化'} 被改动，拒绝这次修复")

    # 五、重试预算
    attempts = iter(['{"sku": "A100"', '{"sku": "A100", "qty": 0, "price_cents": 1999}', '{"sku": "A100", "qty": 1, "price_cents": 1999}'])
    for budget in (2, 3):
        attempts = iter(['{"sku": "A100"', '{"sku": "A100", "qty": 0, "price_cents": 1999}', '{"sku": "A100", "qty": 1, "price_cents": 1999}'])
        log = []
        result = None
        for i in range(budget):
            v, err = parse_layer(next(attempts))
            if err:
                log.append("语法错误"); continue
            try:
                result = LineStrict.model_validate(v); break
            except ValidationError as e:
                log.append("Schema：" + errors(e))
        out(f"retry.{budget}", f"最多尝试 {budget} 次：{' → '.join(log) if log else ''}{' → 成功' if result else ' → 放弃，返回明确的失败'}")


main()
