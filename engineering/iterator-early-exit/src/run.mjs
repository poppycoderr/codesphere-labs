// 提前结束也是迭代协议的一部分：break/throw/解构会调用 return()，生成器的 finally 何时执行，
// 手写游标没有 return 时资源不释放，异步迭代与流，类数组不是可迭代对象，迭代中修改数组。
import { Readable } from 'node:stream';

const out = (k, v) => console.log(`${k}\t${v}`);

// 一个会记录调用的迭代器：模拟分页读取，打开时占用一个「连接」
function pagedCursor(log, pages = 5) {
  let i = 0; log.push('打开连接');
  return {
    [Symbol.iterator]() { return this; },
    next() { return i < pages ? { value: `第 ${++i} 页`, done: false } : (log.push('读完，关闭连接'), { value: undefined, done: true }); },
    return() { log.push('return：关闭连接'); return { done: true }; },
  };
}
function cursorWithoutReturn(log, pages = 5) {
  let i = 0; log.push('打开连接');
  return {
    [Symbol.iterator]() { return this; },
    next() { return i < pages ? { value: `第 ${++i} 页`, done: false } : (log.push('读完，关闭连接'), { value: undefined, done: true }); },
  };
}

// 1. 三种退出方式
{
  const a = []; for (const p of pagedCursor(a)) { if (p === '第 2 页') break; }
  const b = []; try { for (const p of pagedCursor(b)) { if (p === '第 2 页') throw new Error('解析失败'); } } catch {}
  const c = []; for (const p of pagedCursor(c, 2)) { void p; }
  out('exit.break', `for...of 中 break：${a.join(' → ')}`);
  out('exit.throw', `循环体抛错：${b.join(' → ')}`);
  out('exit.done', `正常读完：${c.join(' → ')}（不调用 return）`);
}

// 2. 解构、展开与 Array.from
{
  const a = []; const [first] = pagedCursor(a); void first;
  const b = []; const all = [...pagedCursor(b, 3)];
  out('destructure', `const [first] = cursor 只取一个：${a.join(' → ')}；展开 [...cursor] 读了 ${all.length} 页：${b.join(' → ')}`);
}

// 3. 没有 return 的手写游标
{
  const a = []; for (const p of cursorWithoutReturn(a)) { if (p === '第 2 页') break; }
  out('no-return', `手写游标没有 return，break 之后：${a.join(' → ')}（连接没有关闭）`);
}

// 4. 生成器的 finally
{
  const log = [];
  function* pages() { log.push('打开连接'); try { for (let i = 1; i <= 5; i++) yield i; } finally { log.push('finally：关闭连接'); } }
  for (const p of pages()) { if (p === 2) break; }
  const viaBreak = log.splice(0).join(' → ');
  const g = pages(); g.next(); g.next();          // 手动拿了两页就不管了
  const abandoned = log.splice(0).join(' → ');
  const g2 = pages(); g2.next(); g2.return();
  const viaReturn = log.splice(0).join(' → ');
  out('generator', `生成器里用 try/finally 释放：break 时 ${viaBreak}；手动调用两次 next 后丢弃 ${abandoned}（finally 永远不会执行）；手动调用 return() ${viaReturn}`);
}

// 5. 异步迭代：for await 与可读流
{
  const log = [];
  async function* rows() { log.push('打开游标'); try { for (let i = 1; i <= 5; i++) { await null; yield i; } } finally { await null; log.push('finally：关闭游标'); } }
  for await (const r of rows()) { if (r === 2) break; }
  const stream = Readable.from(['a', 'b', 'c', 'd']);
  for await (const chunk of stream) { if (chunk === 'b') break; }
  out('async', `for await 中 break：${log.join(' → ')}；可读流在 for await 中 break 后 destroyed=${stream.destroyed}`);
}

// 6. 类数组不是可迭代对象
{
  const arrayLike = { length: 2, 0: 'a', 1: 'b' };
  let r; try { for (const x of arrayLike) { void x; } r = '可以遍历'; } catch (e) { r = `${e.constructor.name}：${e.message}`; }
  out('array-like', `{ length: 2, 0: 'a', 1: 'b' } 用 for...of：${r}；Array.from 得到 [${Array.from(arrayLike)}]`);
}

// 7. 迭代中修改数组
{
  const queue = ['任务1', '任务2']; const seen = [];
  for (const t of queue) { seen.push(t); if (seen.length <= 3) queue.push(`追加${seen.length}`); if (seen.length > 10) break; }
  out('mutate', `for...of 遍历数组时往里 push：访问了 [${seen}]（数组迭代器每一步都重新读 length，新加的元素也会被访问）`);
}
out('node', process.version);
