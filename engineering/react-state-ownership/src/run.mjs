// React 的状态属于谁：一次渲染是一份快照、函数式更新、修改嵌套对象后浅复制、Object.is、key 决定组件身份、value || '' 丢掉 0。
import 'global-jsdom/register';
import { createElement as h, useState, memo, version } from 'react';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const out = (k, v) => console.log(`${k}\t${v}`);

// 1. 快照：同一次事件里连续 set
{
  const logs = [];
  function Counter() {
    const [count, setCount] = useState(0);
    return h('div', null,
      h('span', { 'data-testid': 'count' }, String(count)),
      h('button', { onClick: () => { setCount(count + 1); setCount(count + 1); setCount(count + 1); logs.push(count); } }, 'plus3'),
      h('button', { onClick: () => { setCount(c => c + 1); setCount(c => c + 1); setCount(c => c + 1); } }, 'plus3fn'));
  }
  render(h(Counter));
  fireEvent.click(screen.getByText('plus3'));
  const a = screen.getByTestId('count').textContent;
  fireEvent.click(screen.getByText('plus3fn'));
  const b = screen.getByTestId('count').textContent;
  out('snapshot', `同一次点击里连续三次 setCount(count + 1)：显示 ${a}，handler 里 set 之后读到的 count 是 ${logs[0]}；再用三次 setCount(c => c + 1)：显示 ${b}`);
  cleanup();
}

// 2. 修改嵌套数组后只浅复制外层：memo 子组件看到同一个数组引用，不重新渲染
{
  const ItemList = memo(function ItemList({ items }) { return h('span', { 'data-testid': 'n' }, String(items.length)); });
  function Cart() {
    const [cart, setCart] = useState({ items: ['键盘'] });
    return h('div', null,
      h(ItemList, { items: cart.items }),
      h('span', { 'data-testid': 'outer' }, String(cart.items.length)),
      h('button', { onClick: () => { cart.items.push('鼠标'); setCart({ ...cart }); } }, 'mutate'),
      h('button', { onClick: () => setCart(c => ({ ...c, items: [...c.items, '显示器'] })) }, 'copy'));
  }
  render(h(Cart));
  fireEvent.click(screen.getByText('mutate'));
  const inner = screen.getByTestId('n').textContent; const outer = screen.getByTestId('outer').textContent;
  fireEvent.click(screen.getByText('copy'));
  out('nested', `push 进原数组再 setCart({...cart})：外层组件显示 ${outer} 件，memo 子组件仍显示 ${inner} 件；连同数组一起复制后子组件显示 ${screen.getByTestId('n').textContent} 件`);
  cleanup();
}

// 3. Object.is：修改后 set 同一个对象，不会重新渲染
{
  let renders = 0;
  function Profile() {
    renders++;
    const [p, setP] = useState({ name: 'alice' });
    return h('div', null, h('span', { 'data-testid': 'name' }, p.name),
      h('button', { onClick: () => { p.name = 'bob'; setP(p); } }, 'same'));
  }
  render(h(Profile));
  const before = renders;
  fireEvent.click(screen.getByText('same'));
  out('object-is', `修改对象的字段后 setP(同一个对象)：重新渲染 ${renders - before} 次，界面仍显示 ${screen.getByTestId('name').textContent}`);
  cleanup();
}

// 4. key 决定状态归属：用下标做 key，删掉第一行后草稿跑到了下一行
function Row({ label }) {
  const [draft, setDraft] = useState('');
  return h('li', null, h('span', null, label), h('input', { 'aria-label': `备注-${label}`, value: draft, onChange: e => setDraft(e.target.value) }));
}
function Rows({ byIndex }) {
  const [items, setItems] = useState([{ id: 'a', label: '张三' }, { id: 'b', label: '李四' }]);
  return h('div', null,
    h('ul', null, items.map((it, i) => h(Row, { key: byIndex ? i : it.id, label: it.label }))),
    h('button', { onClick: () => setItems(xs => xs.slice(1)) }, 'remove-first'));
}
for (const byIndex of [true, false]) {
  render(h(Rows, { byIndex }));
  fireEvent.change(screen.getByLabelText('备注-张三'), { target: { value: '张三的备注' } });
  fireEvent.click(screen.getByText('remove-first'));
  const v = screen.getByLabelText('备注-李四').value;
  out(`key.${byIndex ? 'index' : 'id'}`, `${byIndex ? '用下标做 key' : '用 id 做 key'}：给张三写了备注后删掉张三，李四那一行的输入框里是「${v}」`);
  cleanup();
}

// 5. 换 key 主动重置状态
{
  function Editor({ userId }) { const [draft, setDraft] = useState(''); return h('input', { 'aria-label': 'draft', value: draft, onChange: e => setDraft(e.target.value), 'data-user': userId }); }
  function Page() {
    const [user, setUser] = useState('u1'); const [keyed, setKeyed] = useState(false);
    return h('div', null, h(Editor, keyed ? { key: user, userId: user } : { userId: user }),
      h('button', { onClick: () => setUser('u2') }, 'switch'), h('button', { onClick: () => { setKeyed(true); setUser('u1'); } }, 'keyed'));
  }
  render(h(Page));
  fireEvent.change(screen.getByLabelText('draft'), { target: { value: '写给 u1 的草稿' } });
  fireEvent.click(screen.getByText('switch'));
  const kept = screen.getByLabelText('draft').value;
  cleanup();
  function Page2() {
    const [user, setUser] = useState('u1');
    return h('div', null, h(Editor, { key: user, userId: user }), h('button', { onClick: () => setUser('u2') }, 'switch'));
  }
  render(h(Page2));
  fireEvent.change(screen.getByLabelText('draft'), { target: { value: '写给 u1 的草稿' } });
  fireEvent.click(screen.getByText('switch'));
  out('key.reset', `切换到用户 u2：同一位置、同一组件类型时草稿保留为「${kept}」；以 userId 作为 key 时草稿是「${screen.getByLabelText('draft').value}」`);
  cleanup();
}

// 6. value || '' 把 0 当成空
{
  function Qty() { const [qty] = useState(0); return h('div', null, h('input', { 'aria-label': 'a', readOnly: true, value: qty || '' }), h('input', { 'aria-label': 'b', readOnly: true, value: qty ?? '' })); }
  render(h(Qty));
  out('falsy', `数量为 0：value={qty || ''} 显示「${screen.getByLabelText('a').value}」，value={qty ?? ''} 显示「${screen.getByLabelText('b').value}」`);
  cleanup();
}
out('react', version);
