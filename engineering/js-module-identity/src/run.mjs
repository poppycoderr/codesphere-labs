// 模块是谁、循环依赖时拿到什么：CommonJS 循环拿到部分初始化的 exports、ESM 循环撞上暂时性死区、实时绑定与值拷贝、
// 模块缓存按解析后的路径做身份（同一个包装了两份、import 与 require 拿到两个实例）。
// 夹具文件在运行时写进临时目录，每个场景在独立的子进程里运行。
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { execFileSync } from 'node:child_process';

const out = (k, v) => console.log(`${k}\t${v}`);
const root = mkdtempSync(join(tmpdir(), 'modid-'));
const write = (rel, text) => { const p = join(root, rel); mkdirSync(dirname(p), { recursive: true }); writeFileSync(p, text); return p; };
const run = rel => { try { return execFileSync(process.execPath, [join(root, rel)], { stdio: ['ignore', 'pipe', 'pipe'] }).toString().trim(); } catch (e) { const o = e.stdout.toString().trim(); return `${o ? o + '\n' : ''}${(e.stderr.toString().match(/^(\w*Error[^\n]*)/m) || ['?'])[0]}`; } };

// 1. CommonJS 循环：拿到部分初始化的 exports
write('cjs/order.cjs', `const pricing = require('./pricing.cjs');\nexports.TAX = 0.13;\nexports.total = p => pricing.withTax(p);\n`);
write('cjs/pricing.cjs', `const order = require('./order.cjs');\nconsole.log('pricing 加载时看到的 order 导出：' + JSON.stringify(Object.keys(order)));\nconst DEFAULT_TAX_CENTS = order.TAX * 100;\nconsole.log('pricing 加载时算出的默认税额：' + DEFAULT_TAX_CENTS);\nexports.withTax = p => p * (1 + order.TAX);\n`);
write('cjs/main.cjs', `const order = require('./order.cjs');\nconsole.log('运行时 total(100)=' + order.total(100));\n`);
out('cjs.cycle', run('cjs/main.cjs').split('\n').join('；'));

// 2. ESM 循环：顶层读取对方的 const 撞上暂时性死区；函数声明会被提升
write('esm/order.mjs', `import { withTax } from './pricing.mjs';\nexport const TAX = 0.13;\nexport function rate() { return 0.13; }\nexport const total = p => withTax(p);\n`);
write('esm/pricing.mjs', `import { TAX, rate } from './order.mjs';\nconsole.log('pricing 顶层调用 order 的函数声明 rate()=' + rate());\nexport const DEFAULT = TAX * 100;\nexport const withTax = p => p * (1 + TAX);\n`);
write('esm/main.mjs', `import { total } from './order.mjs';\nconsole.log(total(100));\n`);
out('esm.cycle', run('esm/main.mjs').split('\n').join('；'));

// 3. 实时绑定与值拷贝
write('bind/counter.mjs', `export let count = 0;\nexport function inc() { count++; }\n`);
write('bind/main.mjs', `import { count, inc } from './counter.mjs';\ninc(); inc();\nconsole.log(count);\n`);
write('bind/counter.cjs', `let count = 0;\nmodule.exports = { count, inc() { count++; module.exports.count = count; } };\n`);
write('bind/main.cjs', `const { count, inc } = require('./counter.cjs');\nconst m = require('./counter.cjs');\ninc(); inc();\nconsole.log(count + ' / ' + m.count);\n`);
out('binding', `ESM 导入 count 后调用两次 inc()：读到 ${run('bind/main.mjs')}（实时绑定）；CommonJS 解构出来的 count 与 通过模块对象读的 count：${run('bind/main.cjs')}`);

// 4. 同一个包装了两份：两个「单例」
const registry = `const id = Math.random().toString(36).slice(2, 6);\nconst items = [];\nmodule.exports = { id, register(x) { items.push(x); }, size: () => items.length };\n`;
write('dup/node_modules/registry/package.json', JSON.stringify({ name: 'registry', version: '2.0.0', main: 'index.js' }));
write('dup/node_modules/registry/index.js', registry);
write('dup/node_modules/plugin/package.json', JSON.stringify({ name: 'plugin', version: '1.0.0', main: 'index.js' }));
write('dup/node_modules/plugin/node_modules/registry/package.json', JSON.stringify({ name: 'registry', version: '1.4.0', main: 'index.js' }));
write('dup/node_modules/plugin/node_modules/registry/index.js', registry);
write('dup/node_modules/plugin/index.js', `require('registry').register('plugin 注册的处理器');\n`);
write('dup/main.cjs', `require('plugin');\nconst r = require('registry');\nconsole.log('应用看到的注册数 ' + r.size() + '（插件依赖的 registry@1.4.0 被装在插件自己的 node_modules 里）');\n`);
out('dup.package', run('dup/main.cjs'));

// 5. 同一个包用 import 和 require 各拿一次：双包风险
write('dual/node_modules/config/package.json', JSON.stringify({ name: 'config', version: '1.0.0', exports: { import: './index.mjs', require: './index.cjs' } }));
write('dual/node_modules/config/index.mjs', `export const state = { env: 'default' };\n`);
write('dual/node_modules/config/index.cjs', `exports.state = { env: 'default' };\n`);
write('dual/main.mjs', `import { createRequire } from 'node:module';\nimport { state } from 'config';\nconst require = createRequire(import.meta.url);\nconst cjs = require('config');\nstate.env = 'prod';\nconsole.log('ESM 一侧设为 prod 后，CommonJS 一侧读到 ' + cjs.state.env + '；两边是同一个对象=' + (cjs.state === state));\n`);
out('dual.package', run('dual/main.mjs'));

// 6. 查询参数不同也是不同的模块实例
write('query/single.mjs', `export const created = Math.random();\n`);
write('query/main.mjs', `const a = await import('./single.mjs');\nconst b = await import('./single.mjs?v=2');\nconst c = await import('./single.mjs');\nconsole.log('同一路径两次导入是同一个实例=' + (a === c) + '；加上 ?v=2 后是同一个实例=' + (a === b));\n`);
out('esm.cache-key', run('query/main.mjs'));
out('node', process.version);
