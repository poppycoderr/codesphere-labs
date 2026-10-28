"""翻到很深的页：from+size 的上限、按 _id 排序、search_after 的决胜字段、翻页之间的写入、PIT 快照与关闭。
客户端只用标准库 urllib 调用 REST 接口。输出 TSV（键、说明）。"""
import json
import urllib.error
import urllib.request

ES = "http://es:9200"
N = 12000


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


def reset():
    req("DELETE", "/products")
    req("PUT", "/products", {"settings": {"number_of_shards": 3, "number_of_replicas": 0},
                             "mappings": {"properties": {"pid": {"type": "keyword"}, "score": {"type": "integer"}}}})
    for start in range(0, N, 2000):
        body = []
        for i in range(start, start + 2000):
            body += [{"index": {"_index": "products", "_id": f"p{i:05d}"}}, {"pid": f"p{i:05d}", "score": i % 100}]
        req("POST", "/_bulk", body, ndjson=True)
    req("POST", "/products/_refresh")


def walk(sort, pit=None, during=None, size=500):
    """用 search_after 从头翻到尾，返回看到的 pid 列表；during(页号) 在每页之后调用，模拟翻页之间的写入。"""
    seen, after, page = [], None, 0
    while True:
        body = {"size": size, "sort": sort, "_source": ["pid"]}
        if after is not None:
            body["search_after"] = after
        if pit:
            body["pit"] = {"id": pit["id"], "keep_alive": "1m"}
            st, r = req("POST", "/_search", body)
            pit["id"] = r.get("pit_id", pit["id"])
        else:
            st, r = req("POST", "/products/_search", body)
        hits = r["hits"]["hits"]
        if not hits:
            return seen
        seen += [h["_source"]["pid"] for h in hits]
        after = hits[-1]["sort"]
        page += 1
        if during:
            during(page)


def summary(seen):
    return f"共 {len(seen)} 条，不同的 {len(set(seen))} 条，重复 {len(seen) - len(set(seen))} 条，遗漏 {N - len(set(seen) & {f'p{i:05d}' for i in range(N)})} 条"


def main():
    reset()
    st, info = req("GET", "/")
    out("env", f"elasticsearch={info['version']['number']}，3 个主分片，{N} 条文档，score 只有 100 个值")

    st, _ = req("POST", "/products/_search", {"from": 9990, "size": 10})
    st2, r2 = req("POST", "/products/_search", {"from": 9991, "size": 10})
    out("from.limit", f"from=9990,size=10：HTTP {st}；from=9991,size=10：HTTP {st2}（{r2['error']['root_cause'][0]['type']}）")

    st, r = req("POST", "/products/_search", {"size": 1, "sort": [{"_id": "asc"}]})
    reason = r.get("error", {}).get("root_cause", [{}])[0].get("reason", "")
    out("sort.id", f"按 _id 排序：HTTP {st}，" + ("被拒绝" if st == 400 else "允许") + ("（_id 的 fielddata 默认禁用，开关是 indices.id_field_data.enabled）" if "indices.id_field_data.enabled" in reason else ""))

    out("after.no-tiebreaker", "search_after 只按 score 排序：" + summary(walk([{"score": "asc"}])))
    out("after.tiebreaker", "search_after 按 score、pid 排序：" + summary(walk([{"score": "asc"}, {"pid": "asc"}])))

    # 翻页之间改分数：把排在后面的文档挪到游标之前，把排在前面的挪到游标之后
    def mover(page):
        if page == 5:
            for i in range(99, 200, 100):           # p00099、p00199：score 99，挪到 0
                req("POST", f"/products/_update/p{i:05d}", {"doc": {"score": 0}})
            for i in range(0, 200, 100):             # p00000、p00100：score 0，挪到 99
                req("POST", f"/products/_update/p{i:05d}", {"doc": {"score": 99}})
            req("POST", "/products/_refresh")

    out("write.no-pit", "不用 PIT、第 5 页后改了 4 个文档的 score：" + summary(walk([{"score": "asc"}, {"pid": "asc"}], during=mover)))
    reset()
    st, pit = req("POST", "/products/_pit?keep_alive=1m")
    pit = {"id": pit["id"]}
    first = pit["id"]
    out("write.pit", "用 PIT、同样的写入：" + summary(walk([{"score": "asc"}, {"pid": "asc"}], pit=pit, during=mover)))
    out("pit.business-sort", "用 PIT、只按 score 排序（自动加 _shard_doc 决胜）：" + summary(walk([{"score": "asc"}], pit=pit)))
    out("pit.id", "PIT 翻页过程中返回的 ID 与打开时相同=" + str(pit["id"] == first))

    st, s = req("GET", "/_nodes/stats/indices/search")
    before = sum(n["indices"]["search"]["open_contexts"] for n in s["nodes"].values())
    st, closed = req("DELETE", "/_pit", {"id": pit["id"]})
    st, s = req("GET", "/_nodes/stats/indices/search")
    after = sum(n["indices"]["search"]["open_contexts"] for n in s["nodes"].values())
    out("pit.close", f"关闭 PIT：succeeded={closed.get('succeeded')}，释放 {closed.get('num_freed')} 个分片上下文；打开的搜索上下文 {before} → {after}")
    st, r = req("POST", "/_search", {"size": 1, "pit": {"id": pit["id"]}, "sort": [{"score": "asc"}]})
    out("pit.after-close", f"关闭后继续使用：HTTP {st}（{r['error']['root_cause'][0]['type']}）")


if __name__ == "__main__":
    main()
