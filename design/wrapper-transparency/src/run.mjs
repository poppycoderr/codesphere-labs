// 包一层要透传什么：this、参数、返回值、Promise 与异常；多层包装的顺序；Proxy 的 get/set 与不变量；缓存代理的键。
const out = (k, v) => console.log(`${k}\t${v}`);

class Account {
  constructor(balance) { this.balance = balance; }
  withdraw(n) { if (n > this.balance) throw new Error('余额不足'); this.balance -= n; return this.balance; }
  async withdrawLater(n) { await null; return this.withdraw(n); }
}

// 1. this
{
  const naive = fn => (...args) => fn(...args);
  const fixed = fn => function (...args) { return fn.apply(this, args); };
  const a = new Account(100);
  a.withdraw = naive(Account.prototype.withdraw);
  let r; try { r = a.withdraw(30); } catch (e) { r = `${e.constructor.name}：${e.message}`; }
  const b = new Account(100);
  b.withdraw = fixed(Account.prototype.withdraw);
  out('this', `箭头函数包装直接调用原函数：${r}；用 fn.apply(this, args)：余额 ${b.withdraw(30)}`);
}

// 2. 返回值与异常
{
  const log = [];
  const naive = fn => function (...args) { log.push('调用'); fn.apply(this, args); };
  const swallow = fn => function (...args) { try { return fn.apply(this, args); } catch (e) { log.push(`记录：${e.message}`); } };
  const a = new Account(100); a.withdraw = naive(Account.prototype.withdraw);
  const b = new Account(10); b.withdraw = swallow(Account.prototype.withdraw);
  out('return', `包装里调用了原函数但没有 return：调用方拿到 ${a.withdraw(30)}（余额其实已是 ${a.balance}）`);
  out('throw', `包装捕获异常只记日志：余额 10 取 30，调用方拿到 ${b.withdraw(30)}，以为成功了`);
}

// 3. Promise：同步的 try/catch/finally 管不到异步的失败与耗时
{
  const events = [];
  const timedNaive = fn => function (...args) {
    const t0 = Date.now();
    try { return fn.apply(this, args); }
    catch (e) { events.push(`失败 ${e.message}`); throw e; }
    finally { events.push(`耗时记录在 Promise 完成之前（${Date.now() - t0 < 5 ? '约 0ms' : '>5ms'}）`); }
  };
  const timed = fn => async function (...args) {
    try { return await fn.apply(this, args); }
    catch (e) { events.push(`失败 ${e.message}`); throw e; }
  };
  const a = new Account(10); a.withdrawLater = timedNaive(Account.prototype.withdrawLater);
  try { await a.withdrawLater(30); } catch {}
  const naiveEvents = events.splice(0).join('；');
  const b = new Account(10); b.withdrawLater = timed(Account.prototype.withdrawLater);
  try { await b.withdrawLater(30); } catch {}
  out('async', `异步方法被拒绝：同步写法的包装记录了「${naiveEvents}」，没有记到失败；await 之后的包装记录了「${events.join('；')}」`);
}

// 4. 多层包装的顺序
{
  const order = [];
  const tag = name => fn => function (...args) { order.push(`${name} 进入`); try { return fn.apply(this, args); } finally { order.push(`${name} 退出`); } };
  const compose = (...ws) => fn => ws.reduceRight((acc, w) => w(acc), fn);
  const f = compose(tag('鉴权'), tag('重试'), tag('计时'))(() => order.push('业务'));
  f();
  out('order', `compose(鉴权, 重试, 计时)：${order.join(' → ')}（写在最前面的包在最外层）`);
}

// 5. Proxy 的 get：indexOf 当布尔值、忘了 return、接收者
{
  const hidden = ['secret', 'token'];
  const user = { secret: 's-1', token: 't-1', name: 'alice', get display() { return `${this.name}（${this.secret ? '有' : '无'}密钥）`; } };
  const naive = new Proxy(user, { get(t, k) { if (hidden.indexOf(k)) return t[k]; return undefined; } });
  out('proxy.indexof', `用 indexOf(key) 当布尔值判断是否隐藏：secret（下标 0）读到 ${naive.secret}，token（下标 1）读到 ${naive.token}，name 读到 ${naive.name}`);
  const noReturn = new Proxy(user, { get(t, k) { if (!hidden.includes(k)) { Reflect.get(t, k); } } });
  out('proxy.no-return', `允许分支里调用了 Reflect.get 却没有 return：name 读到 ${noReturn.name}`);
  const viaTarget = new Proxy(user, { get(t, k) { return hidden.includes(k) ? undefined : t[k]; } });
  const viaReflect = new Proxy(user, { get(t, k, receiver) { return hidden.includes(k) ? undefined : Reflect.get(t, k, receiver); } });
  out('proxy.receiver', `访问器 display 里读 this.secret：转发时用 t[k] 得到「${viaTarget.display}」（this 是原对象，绕过了隐藏）；用 Reflect.get(t, k, receiver) 得到「${viaReflect.display}」`);
}

// 6. Proxy 的 set 与不变量
{
  'use strict';
  const target = {};
  const silent = new Proxy(target, { set(t, k, v) { t[k] = v; } });
  let r; try { (function () { 'use strict'; silent.x = 1; })(); r = '成功'; } catch (e) { r = `${e.constructor.name}`; }
  const frozen = Object.defineProperty({}, 'version', { value: 1, writable: false, configurable: false });
  const lying = new Proxy(frozen, { get(t, k) { return k === 'version' ? 2 : Reflect.get(t, k); } });
  let r2; try { r2 = lying.version; } catch (e) { r2 = `${e.constructor.name}`; }
  out('proxy.set', `set 陷阱写入后没有返回 true：严格模式下赋值 ${r}（值其实已经写进去了：${target.x}）`);
  out('proxy.invariant', `对不可写、不可配置的属性 version=1，get 陷阱返回 2：${r2}`);
}

// 7. 缓存代理的键
{
  const calls = [];
  const memoNaive = fn => { const cache = new Map(); return (...args) => { const key = args.join(','); if (!cache.has(key)) cache.set(key, fn(...args)); return cache.get(key); }; };
  const memo = fn => { const cache = new Map(); return (...args) => { const key = JSON.stringify(args); if (!cache.has(key)) cache.set(key, fn(...args)); return cache.get(key); }; };
  const price = (...parts) => { calls.push(parts.length); return `${parts.length} 段：${parts.join('|')}`; };
  const p1 = memoNaive(price);
  const x = p1('a,b'); const y = p1('a', 'b'); const z1 = p1(1); const z2 = p1('1');
  const p2 = memo(price);
  const y2 = (p2('a,b'), p2('a', 'b')); const w2 = (p2(1), p2('1'));
  out('memo', `键用 args.join(',')：先查 ('a,b') 再查 ('a','b') 得到「${y}」（命中了前者的结果），1 与 '1' 共用一个键；键用 JSON.stringify：('a','b') 得到「${y2}」`);
}
out('node', process.version);
