# 环境支持与 CI 层级

## 一、本地环境

| 依赖 | 版本 | 用于 |
|---|---|---|
| JDK | 21 及以上 | 所有 Java 实验 |
| Maven | 3.9 及以上 | `spring/*`、`design/reading-software-design` |
| Docker 与 Compose v2 | Docker 27 及以上 | 带容器的实验 |
| Python | 3.10 及以上；`make check` 需要 PyYAML | 汇总、断言与结构检查 |

首次运行会把 JUnit Console Standalone、MySQL Connector/J 等 jar 下载到仓库内的 `.cache/`（已忽略），JUnit jar 会校验 SHA-1。

证据的归档环境是 macOS arm64（10 核、32 GB）上的 Docker Desktop。Linux x86_64 上功能结论应当一致，耗时和吞吐会不同。

## 二、固定版本的镜像

| 镜像 | digest |
|---|---|
| `mysql:8.4.11` | `sha256:85b9bf2e29cf836ecb8c2a15a935d4ba0c606631dff1dd79531a11983c638f2a` |
| `apache/kafka:4.3.1` | `sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837` |
| `eclipse-temurin:21-jdk` | `sha256:78ab9771b4650066c3ef748d46e05dbd6094d8bb34e0667a074486812efd655b` |

digest 是多架构清单的摘要，在 arm64 与 amd64 上都可以使用。

## 三、CI 层级

| 层级 | 内容 | 触发 |
|---|---|---|
| 基础检查 | `make check`：目录约定、元数据、Shell 语法、敏感信息扫描 | 每次推送与 PR |
| regular | 只需要 JDK 或 Maven 的实验 | 每次推送与 PR |
| regular-docker | 需要单个 MySQL 或 Kafka 容器的实验 | 每次推送与 PR |
| performance | 数据库性能样板等耗时、依赖稳定资源的实验 | 手动触发与每周定时复核 |

CI 使用与本地相同的 `make verify`，并把各实验的 `build/run` 或 `target/run` 上传为 artifact。性能实验只断言执行计划、读取次数与数量级关系，耗时作为证据保存，不作为 CI 通过条件。
