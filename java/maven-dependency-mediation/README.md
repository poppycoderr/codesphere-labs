# Maven 依赖仲裁

对应文章：[maven-dependency-mediation.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/maven-dependency-mediation.md)。

`src/gen.py` 生成一组实验用的 Maven 工程（groupId 都是 `labs.mediation`）：

- `util` 的两个版本：1.0 只有 `Text.upper`，2.0 多了 `Text.title`；
- `lib-a` 依赖 `util:1.0`，`lib-b` 依赖 `util:2.0`（并调用 `title`），`mid` 依赖 `lib-b`；
- 两个 BOM：`bom-x` 管理 `util:1.0`，`bom-y` 管理 `util:2.0`；
- 10 个应用，代码相同（各调用一次 `lib-a` 与 `lib-b`），只有依赖声明不同。

`src/run-in-container.sh` 在固定 digest 的 Maven 3.10.0 + temurin 25 容器里安装这些库，逐个构建应用，记录构建结果、类路径上 `util` 的版本与运行输出；另外记录带 `-Dverbose` 的依赖树，以及打开 Enforcer 两条规则后的构建结果。解析结果是确定的，`scripts/verify.sh` 把它与预期逐行比较。

## 快速运行

```bash
make verify     # 需要 Docker 与 python3；首次要下载 Maven 插件，约 2 分钟
make evidence
make clean
```
