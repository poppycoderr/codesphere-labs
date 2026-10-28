"""别名切换不等于在线迁移：reindex 之后的新增、更新与删除去了哪里；用源系统版本号追平增量；别名指向多个索引时的写入目标与重复读取；原子切换与回切。
客户端只用标准库 urllib 调用 REST 接口。输出 TSV（键、说明）。"""
import json
import urllib.error
import urllib.request

ES = "http://es:9200"
N = 2000


def req(method, path, body=None, ndjson=False):
    data = None
    if body is not None:
        data = ("".join(json.dumps(x) + "\n" for x in body) if ndjson else json.dumps(body)).encode()
    r = urllib.request.Request(ES + path, data=data, method=method,
                               headers={"Content-Type": "application/x-ndjson" if ndjson else "application/json"})
    try:
        with urllib.request.urlopen(r) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def out(k, v):
    print(f"{k}\t{v}", flush=True)


MAPPING_V1 = {"properties": {"status": {"type": "text"}, "amount": {"type": "integer"}, "ver": {"type": "long"}}}
# v2 把 status 改成 keyword：已有字段的类型不能原地修改，只能建新索引再迁移
MAPPING_V2 = {"properties": {"status": {"type": "keyword"}, "amount": {"type": "integer"}, "ver": {"type": "long"}}}


def setup():
    for i in ("orders_v1", "orders_v2"):
        req("DELETE", f"/{i}")
    req("PUT", "/orders_v1", {"settings": {"number_of_shards": 1, "number_of_replicas": 0}, "mappings": MAPPING_V1,
                              "aliases": {"orders": {"is_write_index": True}}})
    body = []
    for i in range(N):
        body += [{"index": {"_index": "orders", "_id": f"o{i}", "version": 1, "version_type": "external"}},
                 {"status": "PAID", "amount": i, "ver": 1}]
    req("POST", "/_bulk", body, ndjson=True)
    req("POST", "/orders/_refresh")


def changes():
    """reindex 之后源系统里发生的变更：(动作, id, 源版本号, 文档)。"""
    cs = []
    for i in range(N, N + 50):
        cs.append(("index", f"o{i}", 1, {"status": "PAID", "amount": i, "ver": 1}))
    for i in range(0, 30):
        cs.append(("index", f"o{i}", 2, {"status": "REFUNDED", "amount": i, "ver": 2}))
    for i in range(100, 120):
        cs.append(("delete", f"o{i}", 2, None))
    return cs


def apply(index, cs, external):
    body = []
    for act, did, ver, doc in cs:
        meta = {"_index": index, "_id": did}
        if external:
            meta.update(version=ver, version_type="external")
        body.append({act: meta})
        if doc is not None:
            body.append(doc)
    st, r = req("POST", "/_bulk?refresh=true", body, ndjson=True)
    return sum(1 for it in r["items"] for v in it.values() if v["status"] == 409)


def snapshot(index):
    st, r = req("POST", f"/{index}/_search", {"size": 10000, "query": {"match_all": {}}, "_source": ["status", "ver"]})
    return {h["_id"]: (h["_source"]["status"], h["_source"]["ver"]) for h in r["hits"]["hits"]}


def diff(a, b):
    missing = len(set(a) - set(b))
    extra = len(set(b) - set(a))
    stale = sum(1 for k in set(a) & set(b) if a[k] != b[k])
    return f"v2 比 v1 少 {missing} 条、多 {extra} 条、内容不同 {stale} 条"


def reindex():
    req("PUT", "/orders_v2", {"settings": {"number_of_shards": 1, "number_of_replicas": 0}, "mappings": MAPPING_V2})
    # version_type=external：保留源索引里的版本号，后面用源系统版本号追平时才能比较新旧
    st, r = req("POST", "/_reindex?refresh=true", {"source": {"index": "orders_v1"}, "dest": {"index": "orders_v2", "version_type": "external"}})
    return r["created"]


def main():
    st, info = req("GET", "/")
    out("env", f"elasticsearch={info['version']['number']}，{N} 条订单，v2 把 status 从 text 改为 keyword")

    # 一、reindex 完成后，旧索引上又发生了变更，直接切别名
    setup()
    created = reindex()
    apply("orders", changes(), external=True)           # 写入经别名落到 v1
    req("POST", "/_aliases", {"actions": [{"remove": {"index": "orders_v1", "alias": "orders"}},
                                          {"add": {"index": "orders_v2", "alias": "orders", "is_write_index": True}}]})
    v1, v2 = snapshot("orders_v1"), snapshot("orders_v2")
    out("naive.switch", f"reindex 复制 {created} 条后，v1 上新增 50、更新 30、删除 20，直接切别名：{diff(v1, v2)}")
    st, r = req("POST", "/orders/_count", {"query": {"term": {"status": "REFUNDED"}}})
    out("naive.read", f"切换后经别名查已退款订单：{r['count']} 条（v1 里是 30 条）")

    # 二、按源系统版本号追平：变更重放两遍、并且故意把一条旧版本放在最后
    setup()
    reindex()
    cs = changes()
    apply("orders", cs, external=True)
    late = [("index", "o0", 1, {"status": "PAID", "amount": 0, "ver": 1})]   # 迟到的旧版本
    c1 = apply("orders_v2", cs, external=True)
    c2 = apply("orders_v2", cs + late, external=True)
    out("catchup.external", f"按源版本号把变更重放到 v2 两次，最后再来一条 o0 的旧版本：第二遍冲突 {c2} 条（被忽略）；{diff(snapshot('orders_v1'), snapshot('orders_v2'))}")
    setup()
    reindex()
    apply("orders", cs, external=True)
    apply("orders_v2", cs + late, external=False)
    out("catchup.plain", f"同样的重放不带版本号：{diff(snapshot('orders_v1'), snapshot('orders_v2'))}")

    # 三、别名同时指向两个索引
    setup()
    reindex()
    req("POST", "/_aliases", {"actions": [{"add": {"index": "orders_v2", "alias": "orders"}},
                                          {"add": {"index": "orders_v1", "alias": "orders", "is_write_index": False}}]})
    st, r = req("PUT", "/orders/_doc/x1", {"status": "PAID", "amount": 1, "ver": 1})
    out("multi.no-write-index", f"别名指向 v1、v2 且都不是写索引：写入 HTTP {st}（{r['error']['root_cause'][0]['type']}）")
    req("POST", "/_aliases", {"actions": [{"add": {"index": "orders_v2", "alias": "orders", "is_write_index": True}}]})
    st, r = req("PUT", "/orders/_doc/x1?refresh=true", {"status": "PAID", "amount": 1, "ver": 1})
    st2, c = req("POST", "/orders/_count", {"query": {"ids": {"values": ["o5"]}}})
    out("multi.write-index", f"把 v2 设为写索引：写入落在 {r['_index']}；经别名按 id 查 o5 得到 {c['count']} 条（两个索引各一份）")

    # 四、原子切换与回切
    setup()
    reindex()
    st, r = req("POST", "/_aliases", {"actions": [{"remove": {"index": "orders_v1", "alias": "orders"}},
                                                  {"add": {"index": "orders_v2", "alias": "orders", "is_write_index": True}}]})
    st2, a = req("GET", "/_alias/orders")
    out("switch.atomic", f"一个 _aliases 请求里 remove + add：HTTP {st}，别名现在指向 {sorted(a)}")
    st, r = req("POST", "/_aliases", {"actions": [{"remove": {"index": "orders_v2", "alias": "orders"}},
                                                  {"add": {"index": "orders_v1", "alias": "orders", "is_write_index": True}}]})
    st2, a = req("GET", "/_alias/orders")
    out("switch.rollback", f"回切：HTTP {st}，别名指向 {sorted(a)}；v1 没有删除，回切只是改别名")


if __name__ == "__main__":
    main()
