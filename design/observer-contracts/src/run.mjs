// 观察者的契约：重复订阅、通知中退订、once 重入、监听器抛错、同步通知的重入顺序、用普通对象做事件表；
// 手写发布器与 Node 的 EventEmitter、EventTarget 对照。输出每行「键<TAB>事实」。
import { EventEmitter } from 'node:events';

const out = (k, v) => console.log(`${k}\t${v}`);

// 朴素发布器：普通对象做事件表，数组存监听器，forEach 通知，splice 退订
class NaiveEmitter {
  constructor() { this.handlers = {}; }
  on(e, fn) { (this.handlers[e] ||= []).push(fn); }
  off(e, fn) { const a = this.handlers[e]; const i = a.indexOf(fn); if (i >= 0) a.splice(i, 1); }
  once(e, fn) { const w = (...args) => { fn(...args); this.off(e, w); }; this.on(e, w); }
  emit(e, ...args) { (this.handlers[e] || []).forEach(fn => fn(...args)); }
}

// 改进版：Map 做事件表；通知前复制快照；once 先摘除再调用；逐个隔离异常并汇总
class Emitter {
  #handlers = new Map();
  on(e, fn) { const s = this.#handlers.get(e) ?? []; if (!s.includes(fn)) s.push(fn); this.#handlers.set(e, s); return () => this.off(e, fn); }
  off(e, fn) { const s = this.#handlers.get(e); if (!s) return; const i = s.indexOf(fn); if (i >= 0) s.splice(i, 1); }
  once(e, fn) { const w = (...a) => { this.off(e, w); fn(...a); }; return this.on(e, w); }
  emit(e, ...args) {
    const errors = [];
    for (const fn of [...(this.#handlers.get(e) ?? [])]) { try { fn(...args); } catch (err) { errors.push(err); } }
    if (errors.length) throw new AggregateError(errors, `${errors.length} 个监听器失败`);
  }
}

// 1. 重复订阅
{
  const n = new NaiveEmitter(); let c = 0; const fn = () => c++;
  n.on('saved', fn); n.on('saved', fn); n.emit('saved');
  const m = new Emitter(); let d = 0; const g = () => d++;
  m.on('saved', g); m.on('saved', g); m.emit('saved');
  const ee = new EventEmitter(); let x = 0; const h = () => x++;
  ee.on('saved', h); ee.on('saved', h); ee.emit('saved');
  out('dup', `同一个函数订阅两次后通知一次：朴素发布器调用 ${c} 次，改进版（订阅时去重）${d} 次，Node EventEmitter ${x} 次`);
}

// 2. 通知过程中退订自己：朴素发布器跳过了下一个监听器
{
  const n = new NaiveEmitter(); const seen = [];
  const a = () => { seen.push('a'); n.off('tick', a); };
  n.on('tick', a); n.on('tick', () => seen.push('b')); n.on('tick', () => seen.push('c'));
  n.emit('tick');
  const m = new Emitter(); const seen2 = [];
  const a2 = () => { seen2.push('a'); m.off('tick', a2); };
  m.on('tick', a2); m.on('tick', () => seen2.push('b')); m.on('tick', () => seen2.push('c'));
  m.emit('tick');
  const ee = new EventEmitter(); const seen3 = [];
  const a3 = () => { seen3.push('a'); ee.off('tick', a3); };
  ee.on('tick', a3); ee.on('tick', () => seen3.push('b')); ee.on('tick', () => seen3.push('c'));
  ee.emit('tick');
  out('unsubscribe-during-emit', `a 在被通知时退订自己：朴素发布器通知了 [${seen}]（b 被跳过），快照通知 [${seen2}]，Node EventEmitter [${seen3}]`);
}

// 3. once 重入：回调里再次触发同一事件
{
  const n = new NaiveEmitter(); let c = 0;
  n.once('ready', () => { c++; if (c < 5) n.emit('ready'); });
  n.emit('ready');
  const m = new Emitter(); let d = 0;
  m.once('ready', () => { d++; if (d < 5) m.emit('ready'); });
  m.emit('ready');
  const ee = new EventEmitter(); let x = 0;
  ee.once('ready', () => { x++; if (x < 5) ee.emit('ready'); });
  ee.emit('ready');
  out('once-reentrant', `once 的回调里再次触发同一事件：先调用后摘除的朴素 once 执行了 ${c} 次，先摘除后调用 ${d} 次，Node EventEmitter ${x} 次`);
}

// 4. 一个监听器抛错
{
  const n = new NaiveEmitter(); const seen = [];
  n.on('paid', () => seen.push('记账')); n.on('paid', () => { throw new Error('短信服务超时'); }); n.on('paid', () => seen.push('积分'));
  let r; try { n.emit('paid'); r = '没有报错'; } catch (e) { r = `抛出「${e.message}」`; }
  const m = new Emitter(); const seen2 = [];
  m.on('paid', () => seen2.push('记账')); m.on('paid', () => { throw new Error('短信服务超时'); }); m.on('paid', () => seen2.push('积分'));
  let r2; try { m.emit('paid'); r2 = '没有报错'; } catch (e) { r2 = `抛出 ${e.constructor.name}（${e.message}）`; }
  const ee = new EventEmitter(); const seen3 = [];
  ee.on('paid', () => seen3.push('记账')); ee.on('paid', () => { throw new Error('短信服务超时'); }); ee.on('paid', () => seen3.push('积分'));
  let r3; try { ee.emit('paid'); r3 = '没有报错'; } catch (e) { r3 = `抛出「${e.message}」`; }
  out('throw.naive', `第二个监听器抛错：朴素发布器执行了 [${seen}]，${r}（积分没有执行）`);
  out('throw.isolated', `逐个隔离并汇总：执行了 [${seen2}]，${r2}`);
  out('throw.node-emitter', `Node EventEmitter：执行了 [${seen3}]，${r3}`);
}

// 5. EventTarget：监听器抛错不影响后续监听器，错误以未捕获异常报告
{
  const reported = [];
  const handler = e => reported.push(e.message);
  process.on('uncaughtException', handler);
  const t = new EventTarget(); const seen = [];
  t.addEventListener('paid', () => seen.push('记账'));
  t.addEventListener('paid', () => { throw new Error('短信服务超时'); });
  t.addEventListener('paid', () => seen.push('积分'));
  let r; try { t.dispatchEvent(new Event('paid')); r = 'dispatchEvent 正常返回'; } catch { r = 'dispatchEvent 抛出'; }
  await new Promise(res => setTimeout(res, 10));
  process.off('uncaughtException', handler);
  out('throw.event-target', `EventTarget：执行了 [${seen}]，${r}，错误作为未捕获异常报告 ${reported.length} 次（${reported[0]}）`);
}

// 6. 同步通知的重入：监听器在通知过程中改状态并再次通知，后面的监听器先看到新值、后看到旧值
{
  const store = { value: 1, ee: new NaiveEmitter() };
  const set = v => { store.value = v; store.ee.emit('change', v); };
  const order = [];
  store.ee.on('change', v => { if (v === 2) set(3); });          // 规范化：2 一律改成 3
  store.ee.on('change', v => order.push(v));                       // 渲染
  set(2);
  out('reentrant-order', `第一个监听器在收到 2 时把值改成 3：第二个监听器收到的顺序是 [${order}]，最后停在 ${order.at(-1)}，而当前值是 ${store.value}`);
}

// 7. 普通对象做事件表或策略表：继承来的键
{
  const n = new NaiveEmitter();
  let r; try { n.emit('toString'); r = '没有报错'; } catch (e) { r = `${e.constructor.name}：${e.message}`; }
  const strategies = { vip: p => p * 0.8, normal: p => p };
  const pick = tag => strategies[tag] ?? (() => { throw new Error(`未知类型 ${tag}`); });
  let r2; try { r2 = String(pick('constructor')(100)); } catch (e) { r2 = e.message; }
  const safe = new Map(Object.entries(strategies));
  const pick2 = tag => safe.get(tag) ?? (() => { throw new Error(`未知类型 ${tag}`); });
  let r3; try { r3 = String(pick2('constructor')(100)); } catch (e) { r3 = e.message; }
  out('prototype-keys', `普通对象做事件表时 emit('toString')：${r}；策略表按标签查 'constructor'：得到 ${r2.slice(0, 40)}（没有走「未知类型」分支）；改用 Map：${r3}`);
}

// 8. Node EventEmitter 的 error 事件
{
  const ee = new EventEmitter();
  let r; try { ee.emit('error', new Error('连接断开')); r = '没有报错'; } catch (e) { r = `抛出「${e.message}」`; }
  out('error-event', `Node EventEmitter 没有 error 监听器时 emit('error')：${r}`);
}
out('node', process.version);
