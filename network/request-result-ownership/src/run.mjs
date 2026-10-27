// 请求结果归谁：fetch 的错误语义、204 与非 JSON 响应、回调适配的单次完成、迟到的响应覆盖新结果、取消与超时。
import http from 'node:http';

const out = (k, v) => console.log(`${k}\t${v}`);
const sleep = ms => new Promise(r => setTimeout(r, ms));
let serverDone = 0;

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/missing') { res.writeHead(404, { 'content-type': 'text/html' }); return res.end('<h1>Not Found</h1>'); }
  if (url.pathname === '/gone') { res.writeHead(404, { 'content-type': 'application/json' }); return res.end('{"error":"订单不存在"}'); }
  if (url.pathname === '/boom') { res.writeHead(500, { 'content-type': 'text/html' }); return res.end('<h1>Internal Error</h1>'); }
  if (url.pathname === '/empty') { res.writeHead(204); return res.end(); }
  if (url.pathname === '/search') {
    const q = url.searchParams.get('q');
    await sleep(Number(url.searchParams.get('delay')));
    serverDone++;
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ q, items: [`${q}-1`, `${q}-2`] }));
  }
  res.writeHead(200, { 'content-type': 'application/json' }); res.end('{"ok":true}');
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;

// 1. fetch 什么时候 reject
{
  const naive = async path => { try { const r = await fetch(base + path); const body = await r.json(); return `成功（${JSON.stringify(body)}）`; } catch (e) { return `失败（${e.name}）`; } };
  const checked = async path => {
    const r = await fetch(base + path);
    if (!r.ok) return `HTTP ${r.status}，按失败处理`;
    if (r.status === 204) return '204，没有响应体';
    return `成功（${JSON.stringify(await r.json())}）`;
  };
  out('fetch.404', `404 页面：直接 r.json() 的写法 ${await naive('/missing')}；先检查 r.ok 的写法 ${await checked('/missing')}`);
  out('fetch.404-json', `404 但响应体是 JSON：直接 r.json() 的写法 ${await naive('/gone')}；先检查 r.ok 的写法 ${await checked('/gone')}`);
  out('fetch.500', `500 页面：直接 r.json() 的写法 ${await naive('/boom')}；先检查 r.ok 的写法 ${await checked('/boom')}`);
  out('fetch.204', `204 No Content：直接 r.json() 的写法 ${await naive('/empty')}；分开处理 ${await checked('/empty')}`);
  let r; try { await fetch('http://127.0.0.1:1/'); r = '没有报错'; } catch (e) { r = `${e.name}（${e.cause?.code ?? e.message}）`; }
  out('fetch.network', `连不上服务端：fetch 才会 reject，${r}`);
}

// 2. 回调适配：成功回调抛错时失败回调又被调用一次
{
  const calls = [];
  const adaptNaive = (p, onOk, onFail) => p.then(onOk).catch(onFail);
  const adapt = (p, onOk, onFail) => p.then(onOk, onFail);
  const onOk = () => { calls.push('onOk'); throw new Error('渲染失败'); };
  const onFail = e => calls.push(`onFail(${e.message})`);
  await adaptNaive(Promise.resolve(1), onOk, onFail);
  const first = calls.splice(0).join('、');
  await adapt(Promise.resolve(1), onOk, onFail).catch(e => calls.push(`调用方收到 ${e.message}`));
  out('callback.once', `请求成功、但成功回调里抛错：then(onOk).catch(onFail) 依次调用了 ${first}；then(onOk, onFail) 调用了 ${calls.join('、')}`);
}

// 3. 迟到的响应：输入联想先搜 a（慢）、再搜 ab（快）
async function typeahead(strategy) {
  let shown = null; let generation = 0; let controller = null; const log = [];
  async function search(q, delay) {
    const myGen = ++generation;
    if (strategy === 'abort') { controller?.abort(); controller = new AbortController(); }
    try {
      const r = await fetch(`${base}/search?q=${q}&delay=${delay}`, { signal: strategy === 'abort' ? controller.signal : undefined });
      const body = await r.json();
      if (strategy === 'generation' && myGen !== generation) { log.push(`丢弃过期的 ${q}`); return; }
      shown = body.q; log.push(`显示 ${q}`);
    } catch (e) {
      log.push(`${q} ${e.name}`);
    }
  }
  const p1 = search('a', 300);
  await sleep(20);
  const p2 = search('ab', 50);
  await Promise.all([p1, p2]);
  return { shown, log };
}
for (const s of ['naive', 'generation', 'abort']) {
  const before = serverDone;
  const { shown, log } = await typeahead(s);
  await sleep(350);
  const name = { naive: '按到达顺序直接显示', generation: '代际标记丢弃过期结果', abort: 'AbortController 取消上一个请求' }[s];
  out(`stale.${s}`, `先搜 a（300ms）再搜 ab（50ms），${name}：最终显示 ${shown}；过程 ${log.join('、')}；服务端完成了 ${serverDone - before} 个搜索`);
}

// 4. 超时与主动取消是两种错误
{
  let t; try { await fetch(`${base}/search?q=x&delay=300`, { signal: AbortSignal.timeout(50) }); t = '没有超时'; } catch (e) { t = e.name; }
  const c = new AbortController(); setTimeout(() => c.abort(), 50);
  let a; try { await fetch(`${base}/search?q=y&delay=300`, { signal: c.signal }); a = '没有取消'; } catch (e) { a = e.name; }
  await sleep(350);
  out('abort.kinds', `AbortSignal.timeout(50) 触发：${t}；主动 abort()：${a}（调用方可以据此区分「超时」与「用户不要了」）`);
}

server.close();
out('node', process.version);
