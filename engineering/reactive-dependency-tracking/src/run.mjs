// 响应式系统的三本账：依赖每次运行重新收集（分支切换后清理旧依赖）、嵌套 effect 的当前观察者要用栈恢复、批量调度的去重与异常复位；
// 最小实现与 @vue/reactivity 对照；React useSyncExternalStore 的快照稳定性。
import 'global-jsdom/register';
import { reactive, effect as vueEffect } from '@vue/reactivity';
import { createElement as h, useSyncExternalStore } from 'react';
import { render, cleanup } from '@testing-library/react';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const out = (k, v) => console.log(`${k}\t${v}`);

// 最小实现：可选「每次运行前清理依赖」「用栈恢复当前观察者」
function createSystem({ cleanupDeps, stack, skipSelf = true }) {
  const targets = new WeakMap(); let active = null; const effectStack = [];
  function track(t, k) { if (!active) return; let m = targets.get(t); if (!m) targets.set(t, m = new Map()); let s = m.get(k); if (!s) m.set(k, s = new Set()); s.add(active); active.deps.push(s); }
  function trigger(t, k) { const s = targets.get(t)?.get(k); if (!s) return; for (const e of [...s]) if (!skipSelf || e !== active) e.run(); }
  function reactiveObj(o) { return new Proxy(o, { get(t, k, r) { track(t, k); return Reflect.get(t, k, r); }, set(t, k, v, r) { const ok = Reflect.set(t, k, v, r); trigger(t, k); return ok; } }); }
  function effect(fn) {
    const e = { deps: [], run() {
      if (cleanupDeps) { e.deps.forEach(s => s.delete(e)); e.deps.length = 0; }
      const prev = active;
      if (stack) effectStack.push(e);
      active = e;
      try { fn(); } finally { if (stack) { effectStack.pop(); active = effectStack.at(-1) ?? null; } else { active = prev === e ? null : null; } }
    } };
    e.run(); return e;
  }
  return { reactive: reactiveObj, effect };
}

// 1. 分支切换：不清理旧依赖时，无关字段的变化也会让 effect 重跑
for (const [name, sys] of [['naive', createSystem({ cleanupDeps: false, stack: true })], ['cleanup', createSystem({ cleanupDeps: true, stack: true })], ['vue', { reactive, effect: vueEffect }]]) {
  const s = sys.reactive({ loggedIn: true, user: 'alice', guestBanner: '欢迎' });
  let runs = 0;
  sys.effect(() => { runs++; void (s.loggedIn ? s.user : s.guestBanner); });
  s.loggedIn = false; const afterSwitch = runs;
  s.user = 'bob'; s.user = 'carol';
  const label = { naive: '不清理旧依赖的最小实现', cleanup: '每次运行前清理依赖', vue: '@vue/reactivity 3.5.44' }[name];
  out(`branch.${name}`, `${label}：登出后 user 已不再被读取，user 又改了两次，effect 额外重跑 ${runs - afterSwitch} 次`);
}

// 2. 嵌套 effect：不恢复外层观察者时，外层后续读取的依赖丢失
for (const [name, sys] of [['naive', createSystem({ cleanupDeps: true, stack: false })], ['stack', createSystem({ cleanupDeps: true, stack: true })], ['vue', { reactive, effect: vueEffect }]]) {
  const s = sys.reactive({ title: '订单', count: 1 });
  let outer = 0;
  sys.effect(() => { outer++; sys.effect(() => void s.title); void s.count; });
  const before = outer; s.count = 2;
  const label = { naive: '不用栈恢复当前观察者', stack: '用栈恢复', vue: '@vue/reactivity' }[name];
  out(`nested.${name}`, `${label}：外层 effect 在创建内层 effect 之后读取 count，count 变化后外层重跑 ${outer - before} 次`);
}

// 3. 自己修改自己的依赖
{
  const sys = createSystem({ cleanupDeps: true, stack: true, skipSelf: false });
  const s = sys.reactive({ n: 0 }); let r;
  try { sys.effect(() => { s.n = s.n + 1; }); r = `n=${s.n}`; } catch (e) { r = `${e.constructor.name}`; }
  const v = reactive({ n: 0 }); vueEffect(() => { v.n = v.n + 1; });
  out('self-trigger', `effect 里执行 n = n + 1：不排除当前正在运行的 effect 的最小实现得到 ${r}；@vue/reactivity 得到 n=${v.n}（不会自己触发自己）`);
}

// 4. 批量调度：同步触发 vs 去重的微任务队列；任务抛错后调度状态要复位
{
  const s = reactive({ a: 1, b: 2, c: 3 }); let syncRuns = 0;
  vueEffect(() => { syncRuns++; void (s.a + s.b + s.c); });
  const before = syncRuns; s.a = 10; s.b = 20; s.c = 30;
  const queue = new Set(); let flushing = false; let batchedRuns = 0;
  const queueJob = job => { queue.add(job); if (!flushing) { flushing = true; queueMicrotask(() => { try { for (const j of queue) j(); } finally { queue.clear(); flushing = false; } }); } };
  const t = reactive({ a: 1, b: 2, c: 3 });
  const runner = vueEffect(() => { batchedRuns++; void (t.a + t.b + t.c); }, { scheduler: () => queueJob(runner) });
  const beforeB = batchedRuns; t.a = 10; t.b = 20; t.c = 30;
  await null; await null;
  out('batch', `同一轮里改三个字段：同步 effect 重跑 ${syncRuns - before} 次；scheduler 把任务放进按任务去重的微任务队列后重跑 ${batchedRuns - beforeB} 次`);

  const swallow = () => {}; process.on('uncaughtException', swallow);
  let stuck = false; const q2 = new Set(); let runs2 = 0;
  const naiveQueue = job => { q2.add(job); if (!stuck) { stuck = true; queueMicrotask(() => { for (const j of q2) j(); q2.clear(); stuck = false; }); } };
  naiveQueue(() => { throw new Error('渲染失败'); });
  await new Promise(r => setTimeout(r, 0)).catch(() => {});
  naiveQueue(() => runs2++);
  await new Promise(r => setTimeout(r, 0));
  process.off('uncaughtException', swallow);
  out('batch.stuck', `调度器在刷新队列时没有用 finally 复位：一个任务抛错后，之后排进来的任务执行了 ${runs2} 次（flushing 一直是 true）`);
}

// 5. useSyncExternalStore：getSnapshot 每次返回新对象
{
  let state = { count: 1 };
  const subscribe = cb => () => void cb;
  function Bad() { const snap = useSyncExternalStore(subscribe, () => ({ ...state })); return h('span', null, String(snap.count)); }
  function Good() { const snap = useSyncExternalStore(subscribe, () => state); return h('span', null, String(snap.count)); }
  let bad; const origError = console.error; const errors = []; console.error = (...a) => errors.push(String(a[0]).slice(0, 80));
  try { render(h(Bad)); bad = '渲染成功'; } catch (e) { bad = `${e.constructor.name}：${e.message.slice(0, 60)}`; }
  cleanup();
  let good; try { const r = render(h(Good)); good = `渲染成功，显示 ${r.container.textContent}`; } catch (e) { good = e.message; }
  cleanup(); console.error = origError;
  out('uses.snapshot', `getSnapshot 每次返回新对象：${bad}；返回同一个对象：${good}`);
  out('uses.warning', `React 给出的提示：${errors.find(e => e.includes('getSnapshot')) ?? '无'}`);
}
out('vue-reactivity', '3.5.44');
