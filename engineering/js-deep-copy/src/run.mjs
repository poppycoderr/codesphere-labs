// 深拷贝不是一个函数：structuredClone、JSON 往返与手写递归各丢掉了什么，领域对象为什么不该靠通用深拷贝复制。
const out = (k, v) => console.log(`${k}\t${v}`);
const tryRun = f => { try { return f(); } catch (e) { return `${e.constructor.name === 'DOMException' ? 'DOMException ' + e.name : e.constructor.name}：${e.message.split('\n')[0].slice(0, 60)}`; } };

class Money {
  #cents;
  constructor(cents, currency) { if (!Number.isInteger(cents)) throw new Error('金额必须是整数分'); this.#cents = cents; this.currency = currency; }
  get cents() { return this.#cents; }
  plus(o) { return new Money(this.#cents + o.cents, this.currency); }
}

const shared = { tag: '共享' };
const order = {
  id: 7, note: undefined, createdAt: new Date('2026-10-27T09:00:00Z'), ratio: NaN,
  items: new Map([['sku-1', 2]]), tags: new Set(['vip']), a: shared, b: shared,
  get total() { return 42; }, [Symbol('trace')]: 't-1',
};
order.self = order;

// 1. structuredClone
{
  const c = structuredClone({ ...order, self: undefined });
  const withCycle = structuredClone(order);
  out('clone.keeps', `structuredClone：Date 仍是 Date=${c.createdAt instanceof Date}，Map 仍是 Map=${c.items instanceof Map}，NaN 保留=${Number.isNaN(c.ratio)}，undefined 字段保留=${'note' in c}，环保留=${withCycle.self === withCycle}，两处共享的对象仍是同一个=${c.a === c.b}`);
  out('clone.drops', `structuredClone：访问器 total 变成普通数据属性=${Object.getOwnPropertyDescriptor(c, 'total')?.get === undefined}，Symbol 键还在=${Object.getOwnPropertySymbols(c).length > 0}`);
  out('clone.function', `对象里有函数时：${tryRun(() => structuredClone({ id: 1, format: () => '' }))}`);
  const m = structuredClone(new Money(1999, 'CNY'));
  out('clone.class', `复制一个 Money 实例：得到的是 Money=${m instanceof Money}，cents=${m.cents}，调用 plus：${tryRun(() => m.plus(m))}`);
}

// 2. JSON 往返
{
  const j = JSON.parse(JSON.stringify({ ...order, self: undefined }));
  out('json.loses', `JSON 往返：note 字段还在=${'note' in j}，createdAt 是 ${typeof j.createdAt}，items 变成 ${JSON.stringify(j.items)}，NaN 变成 ${j.ratio}，共享对象还是同一个=${j.a === j.b}`);
  out('json.throws', `有环：${tryRun(() => JSON.stringify(order))}；有 BigInt：${tryRun(() => JSON.stringify({ n: 1n }))}`);
}

// 3. 手写递归
{
  function deepClone(o) {
    if (o === null || typeof o !== 'object') return o;
    const copy = Array.isArray(o) ? [] : {};
    for (const k in o) { if (o.hasOwnProperty(k)) copy[k] = deepClone(o[k]); }
    return copy;
  }
  out('naive.cycle', `手写递归遇到环：${tryRun(() => deepClone(order))}`);
  const c = deepClone({ ...order, self: undefined });
  out('naive.loses', `手写递归：createdAt 变成 ${JSON.stringify(c.createdAt)}，items 变成 ${JSON.stringify(c.items)}，共享对象被复制成两份=${c.a !== c.b}`);
  out('naive.own-key', `数据里有一个叫 hasOwnProperty 的字段：${tryRun(() => deepClone({ hasOwnProperty: 'x', id: 1 }))}`);
  const payload = JSON.parse('{"__proto__": {"isAdmin": true}, "name": "eve"}');
  const copied = deepClone(payload);
  out('naive.proto', `复制 JSON.parse 出来的 {"__proto__": {"isAdmin": true}}：副本自身有 isAdmin=${Object.hasOwn(copied, 'isAdmin')}，读 copied.isAdmin 得到 ${copied.isAdmin}（赋值 copy['__proto__'] 改掉了副本的原型）`);
}

// 4. 领域对象：复制要经过构造与校验
{
  const m = new Money(1999, 'CNY');
  const spread = { ...m };
  out('domain', `展开 {...money}：私有字段丢了，cents=${spread.cents}；领域对象应提供自己的复制或用构造器重建：new Money(m.cents, m.currency).cents=${new Money(m.cents, m.currency).cents}`);
}
out('node', process.version);
