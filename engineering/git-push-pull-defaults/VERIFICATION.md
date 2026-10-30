# 验证记录：推送与拉取的默认行为

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 新分支无上游 git push | 报错 has no upstream branch |
| push.autoSetupRemote=true | 自动建立 origin/feature |
| 上游名字不同 | 拒绝推送 |
| 分歧时 git pull | 报错要求选择合并方式 |
| --ff-only / --rebase / --no-rebase | 失败 / 本地提交换身份 / 新增合并提交 |
| amend、cherry-pick | 产生新提交；cherry-pick 的 patch-id 相同 |
| --force-with-lease，未 fetch | 拒绝（stale info） |
| 后台 fetch 之后 --force-with-lease | 放行，alice-2 从远端消失 |
| 加 --force-if-includes | 拒绝 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 远端是本地裸仓库，没有托管平台的分支保护与推送规则。
- 提交时间被固定，所以「什么都不改的 amend」得到同一个提交；正常使用时时间总在变化，amend 总会产生新提交。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-30 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
