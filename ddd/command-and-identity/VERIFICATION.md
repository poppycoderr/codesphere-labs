# 验证记录：请求、可信身份与命令

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 请求体直接绑定到领域对象，JSON 里带 `tenantId`、`ownerId`、`status` | 保存为租户 t-bob、所有者 bob、状态 PAID |
| 绑定到只含 sku、qty 的命令，身份取自上下文，同样的 JSON | 保存为租户 t-alice、所有者 alice、状态 NEW |
| t-alice 的用户按 id 读取 t-bob 的订单，只按 id 查 | 返回该订单 |
| 按（租户，id）查 | not found |
| 表单绑定到有 `setRole` 的对象，参数里多带 `role=admin` | role=admin |
| `@InitBinder` 里只允许 `nickname` | role=member |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- `MockMvc` 不经过真实的 Servlet 容器与网络，认证结果用请求属性模拟；它验证的是 Spring MVC 的绑定行为。
- 只演示了框架绑定与查找条件，没有覆盖授权框架（方法级授权、过滤器链）的配置。
- 命令对象忽略未知字段是 Jackson 3 的默认行为（见 engineering/serialization-compatibility）；Jackson 2 默认会对未知字段报错。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-16 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
