import json, socket, struct, time, urllib.request, urllib.error
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def request(host,path,body=None):
    req=urllib.request.Request(f'http://{host}:8000{path}',data=body)
    if host in ('nginx','tcp'): req=urllib.request.Request(f'http://{host}:8080{path}',data=body)
    try:
        with opener.open(req,timeout=3) as r: return r.status,json.load(r)
    except urllib.error.HTTPError as e: return e.code,json.load(e)
def dns(host):
    name=b'\x03app\x07example\x00'
    query=b'\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00'+name+b'\x00\x01\x00\x01'
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as s:
        s.settimeout(2);s.sendto(query,(host,1053));p=s.recv(512)
    assert p[:2]==query[:2] and struct.unpack('!H',p[6:8])[0]==1
    return '.'.join(str(c) for c in p[-4:])
for _ in range(120):
    try:
        request('nginx','/echo'); request('tcp','/echo'); dns('cache');break
    except (OSError,ValueError) as e:
        last_error=repr(e);time.sleep(.5)
else:raise AssertionError('services not ready: '+last_error)
r={}
status,data=request('nginx','/r302',b'demo=1');r['python302']={'method':data['method'],'body':data['body']}
try:
    opener.open(urllib.request.Request('http://nginx:8080/r307',data=b'demo=1'),timeout=3)
    raise AssertionError('urllib unexpectedly replayed POST')
except urllib.error.HTTPError as e:
    assert e.code==307
    r['python307']='not automatically followed'
r['direct']=request('good','/echo')[1]
r['l7']=request('nginx','/echo')[1];r['tcp']=request('tcp','/echo')[1]
assert r['python302']=={'method':'GET','body':''}
assert r['direct']['peer']!=r['l7']['peer'] and r['l7']['xff']==r['direct']['peer']
assert r['tcp']['peer']!=r['direct']['peer'] and r['tcp']['xff'] is None
for path in ('retry-off','retry-on'):
    status,data=request('nginx','/'+path,b'demo=1');r[path]={'status':status,'role':data['role'],'body':data['body']}
assert r['retry-off']['status']==503 and r['retry-on']['status']==200
assert r['retry-on']['role']=='good' and r['retry-on']['body']=='demo=1'
r['backend_counts']={h:request(h,'/stats')[1]['counts'] for h in ('good','bad')}
assert r['backend_counts']['bad']['/retry-on']==1 and r['backend_counts']['good']['/retry-on']==1
assert '/retry-off' not in r['backend_counts']['good']
r['dns_before']=dns('cache');request('authdns','/switch');r['dns_authority_after']=dns('authdns');r['dns_cached_after']=dns('cache')
assert r['dns_before']==r['dns_cached_after']=='203.0.113.10' and r['dns_authority_after']=='203.0.113.20'
deadline=time.monotonic()+8
while time.monotonic()<deadline:
    value=dns('cache')
    if value=='203.0.113.20':break
    time.sleep(.25)
else:raise AssertionError('cache did not expire')
r['dns_after_expiry']=value
print(json.dumps(r,indent=2))
