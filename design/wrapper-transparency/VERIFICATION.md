# 验证记录：包一层要透传什么

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 箭头函数包装后调用原方法 | TypeError（this 为 undefined）；apply(this, args) 正常 |
| 包装不 return / 吞异常 | 调用方拿到 undefined |
| 同步 try/catch/finally 包异步方法 | 记不到拒绝；await 之后的包装记到失败 |
| compose(鉴权, 重试, 计时) | 鉴权在最外层 |
| Proxy get 用 indexOf 当布尔值 | 下标 0 的字段被隐藏、下标 1 的字段暴露 |
| get 忘了 return | 全部读到 undefined |
| 访问器里读 this.secret | t[k] 转发绕过隐藏；Reflect.get 带 receiver 不绕过 |
| set 陷阱不返回 true | 严格模式 TypeError，值已写入 |
| get 陷阱违反不可配置属性的不变量 | TypeError |
| 缓存键用 join(',') | ('a,b') 与 ('a','b') 碰撞 |
| TypeScript 7.0.2 两种装饰器模式 | 各自编译运行正常；混用报 TS1241/TS1270，产出的 JS 运行时 TypeError |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只覆盖 Node 24.21.0 的 V8 行为与 TypeScript 7.0.2；装饰器的标准语义以 TypeScript 的实现为准，浏览器原生支持情况另查。
- 耗时只用于说明记录发生在 Promise 完成之前，不作计时结论。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-27 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
