"""bulk 返回 200 也可能有失败：逐项错误、只看状态码的客户端、整批重试与只重试失败项、create 冲突、外部版本号防止旧写覆盖新写。
客户端只用标准库 urllib 调用 REST 接口。输出 TSV（键、说明）。"""
import json
import urllib.error
import urllib.request

ES = "http://es:9200"


def req(method, path, body=None, ndjson=False):
    data = None
    if body is not None:
        data = ("".join(json.dumps(x, ensure_ascii=False) + "\n" for x in body) if ndjson else json.dumps(body)).encode()
    r = urllib.request.Request(ES + path, data=data, method=method,
                               headers={"Content-Type": "application/x-ndjson" if ndjson else "application/json"})
    try:
        with urllib.request.urlopen(r) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def out(k, v):
    print(f"{k}\t{v}", flush=True)


def reset():
    req("DELETE", "/orders")
    req("PUT", "/orders", {"settings": {"number_of_replicas": 0},
                           "mappings": {"dynamic": "strict", "properties": {"sku": {"type": "keyword"}, "qty": {"type": "integer"},
                                                                          "status": {"type": "keyword"}}}})


def count():
    req("POST", "/orders/_refresh")
    return req("GET", "/orders/_count")[1]["count"]


def items_summary(resp):
    res = []
    for it in resp["items"]:
        op, r = next(iter(it.items()))
        res.append(f"{r.get('_id', '-')}:{r['status']}" + (f"({r['error']['type']})" if "error" in r else ""))
    return res


_, info = req("GET", "/")
out("env", f"elasticsearch={info['version']['number']}")

# 1. 部分失败
reset()
docs = [{"sku": "A1", "qty": 1}, {"sku": "A2", "qty": "两件"}, {"sku": "A3", "qty": 3}, {"sku": "A4", "qty": 4, "note": "x"}, {"sku": "A5", "qty": 5}]
body = []
for i, d in enumerate(docs, 1):
    body += [{"index": {"_index": "orders", "_id": f"o{i}"}}, d]
st, resp = req("POST", "/_bulk", body, ndjson=True)
out("partial.http", f"一次 bulk 5 条：HTTP 状态 {st}，响应顶层 errors={resp['errors']}")
out("partial.items", f"逐项结果：{items_summary(resp)}")
out("partial.count", f"只看 HTTP 状态的客户端认为 5 条都写入了，索引里实际 {count()} 条")

# 2. 重试：整批重试与只重试失败项
failed = [i for i, it in enumerate(resp["items"]) if "error" in next(iter(it.values()))]
fixed = {1: {"sku": "A2", "qty": 2}, 3: {"sku": "A4", "qty": 4}}
retry = []
for i in failed:
    retry += [{"index": {"_index": "orders", "_id": f"o{i + 1}"}}, fixed[i]]
st, r2 = req("POST", "/_bulk", retry, ndjson=True)
out("retry.failed_only", f"修正数据后只重试失败的 {len(failed)} 条（带原来的 _id）：{items_summary(r2)}；索引里 {count()} 条")

reset()
auto = []
for d in [{"sku": "B1", "qty": 1}, {"sku": "B2", "qty": "两件"}, {"sku": "B3", "qty": 3}]:
    auto += [{"index": {"_index": "orders"}}, d]
req("POST", "/_bulk", auto, ndjson=True)
auto_fixed = [x if x != {"sku": "B2", "qty": "两件"} else {"sku": "B2", "qty": 2} for x in auto]
req("POST", "/_bulk", auto_fixed, ndjson=True)
req("POST", "/orders/_refresh")
st, s = req("POST", "/orders/_search?size=0", {"aggs": {"by": {"terms": {"field": "sku"}}}})
out("retry.whole_auto_id", "不指定 _id、整批重试：" + "，".join(f"{b['key']} {b['doc_count']} 条" for b in s["aggregations"]["by"]["buckets"]))

# 3. create 遇到已存在的 _id
reset()
req("POST", "/_bulk", [{"index": {"_index": "orders", "_id": "c1"}}, {"sku": "C1", "qty": 1}], ndjson=True)
st, r3 = req("POST", "/_bulk", [{"create": {"_index": "orders", "_id": "c1"}}, {"sku": "C1", "qty": 9},
                                {"create": {"_index": "orders", "_id": "c2"}}, {"sku": "C2", "qty": 2}], ndjson=True)
out("create.conflict", f"用 create 重放：HTTP {st}，{items_summary(r3)}；c1 的 qty 仍是 {req('GET', '/orders/_doc/c1')[1]['_source']['qty']}")

# 4. 旧写覆盖新写
reset()
req("PUT", "/orders/_doc/v1?refresh=true", {"sku": "V", "qty": 1, "status": "paid"})
req("PUT", "/orders/_doc/v1?refresh=true", {"sku": "V", "qty": 1, "status": "shipped"})   # 新状态先到
req("PUT", "/orders/_doc/v1?refresh=true", {"sku": "V", "qty": 1, "status": "paid"})      # 延迟的旧消息后到
out("order.lww", f"不带版本号：先写 shipped，延迟的 paid 后到，最终状态 {req('GET', '/orders/_doc/v1')[1]['_source']['status']}")
reset()
req("PUT", "/orders/_doc/v1?version=1&version_type=external", {"sku": "V", "qty": 1, "status": "paid"})
req("PUT", "/orders/_doc/v1?version=3&version_type=external", {"sku": "V", "qty": 1, "status": "shipped"})
st, r4 = req("PUT", "/orders/_doc/v1?version=2&version_type=external", {"sku": "V", "qty": 1, "status": "paid"})
doc = req("GET", "/orders/_doc/v1")[1]
out("order.external", f"用源系统的版本号（version_type=external）：版本 2 的旧消息后到，HTTP {st}（{r4.get('error', {}).get('type')}），最终状态 {doc['_source']['status']}，_version={doc['_version']}")
