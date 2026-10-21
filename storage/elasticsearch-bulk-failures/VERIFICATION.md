# 验证记录：bulk 的部分失败、重试与版本号

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 5 条里有类型错误与未声明字段 | HTTP 200，`errors=true`；o2 `document_parsing_exception`、o4 `strict_dynamic_mapping_exception`，索引里 3 条 |
| 带 `_id` 只重试失败的 2 条 | 都是 201，索引里 5 条 |
| 不指定 `_id` 整批重试 | B1、B3 各 2 条，B2 1 条 |
| `create` 遇到已存在的 `_id` | HTTP 200，c1 逐项 409，c2 201；c1 未被覆盖 |
| 不带版本号，旧消息后到 | 最终状态被旧消息覆盖为 paid |
| `version_type=external`，版本 2 在版本 3 之后到 | 409 拒绝，保持 shipped，`_version=3` |

### 写入队列拒绝与失败项退避（2026-10-21 新增）

实验把 write 工作者和队列容量均设为 1，write_coordination=2、队列 1000。4 个分片、0 副本、关闭周期刷新；24 个客户端同时发送各 100 条固定文档，单文档重复 100 次固定短语。最多 3 轮负载尝试，见 src/queue_rejections.py。

断言：至少一个 HTTP 200 的 bulk 同时有 201 与 429；429 类型必须为 es_rejected_execution_exception；write.rejected 增加而协调池拒绝不增加。负载结束后，最多 8 轮、从 50ms 指数退避且封顶 1 秒，只重试已明确返回 429 的项，每批 100 条。任何其他错误或结果不明直接失败，不盲目重放。

最后核对总数、所有 ID 的 ordinal 内容和版本 1，证明没有重发已成功项。实际数字读取 evidence/queue-summary.json；拒绝文档数与重试请求数受调度影响，不能写成固定容量或性能阈值。一个被拒绝的分片任务会对应多个文档，不能把 write.rejected 当作失败文档数。

原始响应、重试轨迹与节点统计全部归档，见 evidence/README.md。退避发生在首轮并发负载排空后，未实现持续洪峰下的自适应控制；没有测试协调池满、网络超时、主分片失败或有副本的拓扑。

### GET 可见性对照

独立 get-lab 索引关闭周期刷新：search=0，实时 GET found=true，realtime=false 得 404；手动 refresh 后 search=1。只验证 API 可见性，不识别实际使用 translog 还是 internal searcher。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 已触发 write 队列拒绝；它不是所有 429 原因的覆盖。节点限 2 CPU、1536 MiB，堆 512 MiB；小队列用于放大机制，不是生产建议。
- 单节点、零副本；副本写入失败与分片不可用时的逐项错误没有覆盖。
- 外部版本号要求源系统能给出单调递增的版本，本实验直接写死。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-21 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |

2026-10-21 后续：补齐真实 write 队列逐项 429、有限退避只重试失败项、所有最终版本 1、GET 可见性对照。正文尚未回写，交 Claude review。
