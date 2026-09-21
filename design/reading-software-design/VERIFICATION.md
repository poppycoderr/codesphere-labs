# 验证记录：Spring 容器、Kafka Producer 与 JDK Stream 的模型切片

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. Spring：`refresh()` 前有 7 个 Bean 定义、0 个业务实例；先创建被依赖的 `Repo`；懒加载的 `Report` 按需创建；`BeanFactoryPostProcessor` 可以修改定义
2. Kafka：`linger.ms=500` 时 300 次 `send()` 在几毫秒内返回，第一个回调在 500ms 之后，300 条消息合成 1 个生产请求
3. Stream：终止操作前不执行任何中间操作；`findFirst` 只过滤了 7 个元素；元素逐个流过流水线；`sorted` 是有状态操作

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：jdk 21.0.5、spring_framework 7.0.9、kafka_clients 4.3.1、kafka_broker 4.3.1。

## 三、执行步骤

`scripts/verify.sh` 依次执行：

1. `mvn dependency:copy-dependencies` 取得 spring-context 7.0.9 与 kafka-clients 4.3.1
2. 在宿主机运行 Stream 与 Spring 切片
3. 启动单节点 Kafka 4.3.1（`compose.yaml`，不暴露端口），在共享其网络的 JDK 21 容器中运行 Kafka 切片

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/spring-slice.txt` | Spring 容器切片 |
| `evidence/kafka-slice.txt` | Kafka Producer 切片 |
| `evidence/stream-slice.txt` | Stream 切片 |
| `evidence/dependencies.txt` | 解析到的 jar |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- Kafka 的耗时（`send()` 返回、第一个回调、`flush`）随机器和容器调度变化；文章中是 8.2ms、515ms，本仓库归档的一次是 6.6ms、514ms。稳定的结论是「send() 远快于 linger.ms、回调晚于 linger.ms、300 条合成 1 个请求」。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 文章发布时 | 在会话临时目录中实测，数字见文章 | — |
| 2026-09-22 | 迁入本仓库，全部断言通过 | 见上文「误差、限制」中与文章不同的数字 |
