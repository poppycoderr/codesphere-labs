# codesphere-labs 协作约定

本文件写给在本仓库工作的 AI 编码代理（Claude Code、Codex 等），人工贡献者遵循同样的规则。

本仓库是 [codesphere](https://github.com/poppycoderr/codesphere) 技术文章的伴生实验仓库：保存可运行示例、验证脚本和原始证据。修改前先读：

- [README.md](README.md)：实验列表与目录约定
- [docs/verification-standard.md](docs/verification-standard.md)：验证级别、黄金样板与轻量实验、完成定义
- [docs/evidence-format.md](docs/evidence-format.md)：`experiment.yaml` 字段、证据目录与规范化规则
- [docs/article-integration.md](docs/article-integration.md)：文章与实验的同步流程
- [CONTRIBUTING.md](CONTRIBUTING.md)：脚本约定与性能数据的写法

## 核心规则

1. **先跑实验，再写结论。** 结论来自 `evidence/` 中的原始输出，不挑选支持预设结论的输出；实验结果与文章不一致时改文章。
2. **一条入口。** 每个实验用 `make verify` 完成运行与断言，失败返回非零退出码；`make evidence` 重新采集证据。
3. **不提交未经验证的空壳。** 没有跑通并归档证据的实验不建目录。
4. **不泄露环境信息。** 不写用户名、主机名、个人绝对路径、真实密码和 Token；提交前运行 `make check`。
5. **性能数据只断言相对关系。** 写清预热、采样次数、并发度、统计口径和资源限制，不用严格耗时阈值判断成败。
6. **容器隔离。** compose 项目名使用 `csl-` 前缀，不暴露宿主机端口，`make clean` 只删除本实验的资源。

## 提交与 PR

- **提交信息**：英文 Conventional Commits，`<type>(<可选 scope>): <subject>`，小写祈使句，只有一行，没有正文。一个提交只做一件事。不加 emoji、不加 AI 署名、不加 `Co-Authored-By` 尾注。
- **scope** 使用专题名，例如 `feat(storage): ...`、`fix(java): ...`；仓库级改动用 `build`、`ci`、`docs`，不带 scope。
- **PR 标题**与提交格式相同；PR 描述写功能本身，不加 AI 生成的页脚。
- 只在作者要求时提交、推送或开 PR；不经明确同意不强制推送、不改写已推送的历史。
- 文章通过固定 commit 引用本仓库，改写历史会让这些链接失效；确需改写时同步更新 codesphere 中的链接。

## 语言

与作者交流使用中文。实验文档、验证记录使用中文，与 codesphere 保持一致；代码标识符、提交信息和 PR 标题使用英文。
