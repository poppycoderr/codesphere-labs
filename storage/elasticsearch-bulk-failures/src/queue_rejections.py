"""写入线程池打满时的逐项 429：保存每个 bulk 的逐项结果，只在负载过去后按退避重试明确返回 429 的项；最后对照实时 GET 与搜索的可见性。"""
import concurrent.futures
import json
import pathlib
import threading
import time
import urllib.error
import urllib.request

OUT = pathlib.Path(__import__('sys').argv[1])
ES = 'http://es:9200'

def req(method, path, body=None, ndjson=False):
    data = None if body is None else (''.join(json.dumps(x)+'\n' for x in body) if ndjson else json.dumps(body)).encode()
    r = urllib.request.Request(ES+path, data=data, method=method, headers={'Content-Type':'application/x-ndjson' if ndjson else 'application/json'})
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            return resp.status, json.loads(resp.read() or b'{}')
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read())

def normalize(value):
    if isinstance(value,list): return [normalize(x) for x in value]
    if isinstance(value,dict):
        return {('<node>' if k in node_ids else k): ('<timestamp>' if k=='timestamp' else '<container-address>' if k in {'host','ip','transport_address'} else normalize(v)) for k,v in value.items()}
    return value

node_ids = set(req('GET','/_nodes')[1]['nodes'])

def save(name, value):
    value = normalize(value)
    (OUT/name).write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n')

def bulk(ids):
    body=[]
    for doc_id in ids:
        body += [{'index':{'_index':'queue-lab','_id':doc_id}}, {'payload': 'alpha beta gamma delta ' * 100, 'ordinal':int(doc_id[1:])}]
    return req('POST','/_bulk',body,True)

assert req('GET','/')[1]['version']['number']=='9.5.5'
save('queue-settings.json',req('GET','/_nodes/thread_pool')[1])
req('DELETE','/queue-lab')
assert req('PUT','/queue-lab',{'settings':{'number_of_shards':4,'number_of_replicas':0,'refresh_interval':'-1'}})[0]==200
before=req('GET','/_nodes/stats/thread_pool')[1]
save('queue-before.json',before)
responses=[]; all_ids=[]; succeeded=set(); failed=set()
for wave in range(3):
    gate=threading.Barrier(24)
    batches=[[f'd{wave*2400+b*100+i}' for i in range(100)] for b in range(24)]
    def send(ids):
        gate.wait(timeout=30)
        return ids,bulk(ids)
    with concurrent.futures.ThreadPoolExecutor(max_workers=24) as pool:
        for ids,(status,r) in pool.map(send,batches):
            all_ids+=ids
            responses.append({'sent':ids,'http_status':status,'response':r})
            assert status==200 and len(r['items'])==len(ids), (status,r)
            for doc_id,item in zip(ids,r['items'],strict=True):
                value=item['index']; assert value['_id']==doc_id
                if value['status']==429:
                    assert value['error']['type']=='es_rejected_execution_exception',value
                    failed.add(doc_id)
                else:
                    assert value['status']==201,value
                    succeeded.add(doc_id)
    if failed:
        break
mixed=sum(any(i['index']['status']==201 for i in r['response']['items']) and any(i['index']['status']==429 for i in r['response']['items']) for r in responses)
assert mixed>0
save('queue-initial-bulk.json',responses)
after=req('GET','/_nodes/stats/thread_pool')[1]; save('queue-after.json',after)
def rejected(stats,pool):
    return sum(n['thread_pool'][pool]['rejected'] for n in stats['nodes'].values())
write_delta=rejected(after,'write')-rejected(before,'write')
coord_delta=rejected(after,'write_coordination')-rejected(before,'write_coordination')
assert failed and succeeded and write_delta>0 and coord_delta==0
# 一次拒绝的是分片级的写入任务，会让同一分片组里的多条文档一起失败；等首轮负载结束后再顺序重试
pending=set(failed); trace=[]
for attempt in range(8):
    if not pending: break
    delay=min(0.05*2**attempt,1.0)
    time.sleep(delay)
    retry_ids=sorted(pending)
    next_pending=set()
    for start in range(0,len(retry_ids),100):
        ids=retry_ids[start:start+100]
        assert not set(ids)&succeeded
        status,r=bulk(ids)
        trace.append({'attempt':attempt+1,'backoff_seconds':delay,'sent':ids,'http_status':status,'response':r})
        assert status==200 and len(r['items'])==len(ids)
        for doc_id,item in zip(ids,r['items'],strict=True):
            value=item['index']
            if value['status']==429: next_pending.add(doc_id)
            else:
                assert value['status']==201,value
                succeeded.add(doc_id)
    pending=next_pending
save('queue-retries.json',trace)
assert not pending, pending
req('POST','/queue-lab/_refresh')
count=req('GET','/queue-lab/_count')[1]['count']; assert count==len(all_ids)
versions=[]
for start in range(0,len(all_ids),200):
    docs=req('POST','/queue-lab/_mget',{'ids':all_ids[start:start+200]})[1]['docs']
    assert all(d.get('found') and d['_version']==1 and d['_source']['ordinal']==int(d['_id'][1:]) for d in docs)
    versions += [{'_id':d['_id'],'_version':d['_version']} for d in docs]
save('queue-final-versions.json',versions)
summary={'mixed_http_200_bulk':mixed,'submitted':len(all_ids),'initial_429':len(failed),'initial_success':len(all_ids)-len(failed),'write_rejected_delta':write_delta,'coordination_rejected_delta':coord_delta,'retry_requests':len(trace),'final_count':count,'all_versions_one':True,'retry_successful_items':0}
save('queue-summary.json',summary)
print(json.dumps(summary,sort_keys=True))
# 只验证可见性：通过 REST 观察不到引擎内部走了哪条读取路径
req('DELETE','/get-lab')
req('PUT','/get-lab',{'settings':{'refresh_interval':'-1','number_of_replicas':0}})
req('PUT','/get-lab/_doc/one',{'value':1})
observed={'search_before':req('GET','/get-lab/_search')[1]['hits']['total']['value'], 'get_before':req('GET','/get-lab/_doc/one')[1]['found'], 'nonrealtime_before':req('GET','/get-lab/_doc/one?realtime=false')[0]}
req('POST','/get-lab/_refresh')
observed['search_after']=req('GET','/get-lab/_search')[1]['hits']['total']['value']
assert observed=={'search_before':0,'get_before':True,'nonrealtime_before':404,'search_after':1}, observed
save('get-visibility.json',observed)
print(json.dumps(observed,sort_keys=True))
