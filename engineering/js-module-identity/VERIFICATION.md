# 验证记录：模块是谁

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| CommonJS 循环 | 加载时对方 exports 为空，算出 NaN；运行时调用正常 |
| ESM 循环 | 函数声明可调用；const 抛 ReferenceError（暂时性死区） |
| 导出可变变量 | ESM 读到 2（实时绑定）；CommonJS 解构得到 0 |
| 同一个包装了两份 | 插件注册到另一个实例，应用看到 0 个 |
| import 与 require 同一个双格式包 | 两个不同的对象 |
| ?v=2 查询参数 | 不同的模块实例 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只覆盖 Node 24.21.0 的模块加载器；打包工具（webpack、Vite 等）对循环与重复包的处理各不相同。
- 夹具里的包是手写的最小结构，不涉及包管理器的去重算法。

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
