# 文章与实验的同步流程

新增或重写 codesphere 文章时：

1. 列出草稿中所有需要实验支持的结论（「实测」「默认」「性能提升」「一定发生」）。
2. 检查本仓库是否已有对应实验，优先扩展。
3. 新建实验：复制 `shared/templates/experiment/`，写代码、`scripts/verify.sh` 和断言。
4. 运行 `make verify`，再 `make evidence`，先有证据再写结论。
5. 写 `VERIFICATION.md`，说明范围、误差和反例。
6. 按证据修改文章，不让实验迁就原稿。
7. 提交并推送本仓库，记下 commit。
8. 在文章末尾「参考资料」之前加入「配套实验」，链接实验目录与 `VERIFICATION.md`，使用固定 commit：

   ```markdown
   **配套实验**

   - [codesphere-labs/<专题>/<实验>](https://github.com/poppycoderr/codesphere-labs/tree/<commit>/<专题>/<实验>)：一句话说明验证了什么（[验证记录](https://github.com/poppycoderr/codesphere-labs/blob/<commit>/<专题>/<实验>/VERIFICATION.md)）
   ```

9. 构建 codesphere，确认链接有效、版本与数字和证据一致。
10. 以后升级依赖或重跑实验，同时判断是否需要回写文章，并在「验证历史」中记录。
