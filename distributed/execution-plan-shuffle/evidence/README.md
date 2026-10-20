# 证据索引

plans.txt、cache-plan.txt 为完整计划；events/ 为完整事件日志；stages.json 为 StageCompleted 中提取的原始 stage 信息；results.json 和 stdout.txt 为断言摘要；environment.txt 为环境。

由 `make evidence` 重新运行并断言后生成。原始业务输出与解释分离；时间、端口、容器地址及调度数据可随运行变化。除固定路径的隐私规范化（如有，在此逐项记录）外，不修改结果、数值、执行计划或断言依据。运行日志和下载日志保留在忽略的 build/，不当作已验证结论。
