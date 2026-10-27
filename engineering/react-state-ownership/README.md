# React 的状态属于谁

对应文章：[react-state-ownership.md](https://github.com/poppycoderr/codesphere/blob/master/docs/web/react-state-ownership.md)。

`src/run.mjs` 用 React 19.3.1 与 Testing Library 在 jsdom 里渲染几个最小组件（不使用 JSX，直接 createElement），逐项观察渲染快照、函数式更新、修改嵌套对象后浅复制与 memo、Object.is 比较、key 与组件身份。

```bash
make verify     # 需要 Docker；约 20 秒
make evidence   # 重新采集 evidence/
make clean
```
