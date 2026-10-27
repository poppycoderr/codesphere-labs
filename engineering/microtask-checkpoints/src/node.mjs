// 微任务什么时候执行（Node 部分）：各队列的顺序、resolve 一个 Promise 要多等两拍、先 resolve 待定的 Promise 后 reject 无效、
// await 不阻塞线程、微任务排队会推迟定时器。
const out = (k, v) => console.log(`${k}\t${v}`);
const tick = () => new Promise(r => setTimeout(r, 5));

// 1. 顺序
{
  const log = [];
  setTimeout(() => log.push('setTimeout'), 0);
  Promise.resolve().then(() => log.push('Promise.then'));
  queueMicrotask(() => log.push('queueMicrotask'));
  process.nextTick(() => log.push('nextTick'));
  log.push('同步代码');
  await tick();
  out('node.order.esm', `ES 模块顶层：${log.join(' → ')}`);
}

// 2. resolve(值) 与 resolve(Promise)、返回 Promise 的 then 回调：多出来的两拍
{
  const log = [];
  new Promise(r => r(Promise.resolve())).then(() => log.push('A：resolve(另一个 Promise)'));
  new Promise(r => r(1)).then(() => log.push('B：resolve(值)'));
  Promise.resolve().then(() => log.push('C：第 1 拍')).then(() => log.push('C：第 2 拍')).then(() => log.push('C：第 3 拍'));
  await tick();
  out('node.adopt', log.join(' → '));
  const log2 = [];
  Promise.resolve().then(() => Promise.resolve('x')).then(() => log2.push('then 回调返回 Promise'));
  Promise.resolve().then(() => 'x').then(() => log2.push('then 回调返回值'));
  Promise.resolve().then(() => log2.push('对照 1')).then(() => log2.push('对照 2')).then(() => log2.push('对照 3')).then(() => log2.push('对照 4'));
  await tick();
  out('node.adopt-then', log2.join(' → '));
}

// 3. 状态锁定：先 resolve 一个待定的 Promise，之后再 reject 不起作用
{
  let resolveInner;
  const inner = new Promise(r => { resolveInner = r; });
  let outerReject;
  const outer = new Promise((res, rej) => { res(inner); outerReject = rej; });
  outerReject(new Error('超时'));
  let state = '待定';
  outer.then(v => { state = `兑现：${v}`; }, e => { state = `拒绝：${e.message}`; });
  await tick(); const before = state;
  resolveInner('库存 3');
  await tick();
  out('node.locked', `外层先 resolve(一个还在等待的 Promise)，随后 reject(超时)：reject 之后外层仍是「${before}」，内层完成后外层「${state}」`);
}

// 4. await 不阻塞线程
{
  const log = [];
  const slow = async () => { log.push('开始等待'); await new Promise(r => setTimeout(r, 20)); log.push('等待结束'); };
  const p = slow();
  setTimeout(() => log.push('别的定时器'), 5);
  log.push('await 之后的同步代码先跑');
  await p;
  out('node.await', log.join(' → '));
}

// 5. 微任务排队推迟定时器
{
  const log = []; let n = 0;
  setTimeout(() => log.push(`setTimeout 执行时，微任务已经跑了 ${n} 个`), 0);
  const spin = () => { n++; if (n < 10000) queueMicrotask(spin); };
  queueMicrotask(spin);
  await tick();
  out('node.starve', log[0]);
}
const { execFileSync } = await import('node:child_process');
out('node.order.cjs', `CommonJS 主模块：${execFileSync(process.execPath, [new URL('./order.cjs', import.meta.url).pathname]).toString().trim()}`);
out('node', process.version);
