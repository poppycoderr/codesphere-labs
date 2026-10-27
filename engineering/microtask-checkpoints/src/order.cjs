// CommonJS 主模块里同样的调度
const log = [];
setTimeout(() => { log.push('setTimeout'); console.log(log.join(' → ')); }, 0);
Promise.resolve().then(() => log.push('Promise.then'));
queueMicrotask(() => log.push('queueMicrotask'));
process.nextTick(() => log.push('nextTick'));
log.push('同步代码');
