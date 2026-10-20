# Spark 物理计划、倾斜分区与加盐结果等价性：验证记录

## 一、结论

过滤投影计划没有 Exchange，groupBy count 有 Exchange 和局部聚合。10,000 行中 8,000 行为热点键，按 key 分区最大 8,460 行；按 key、id%16 分区后最大 4,090 行，二次计数合并逐键等价。新 DataFrame 未自动缓存，cache/count 后有内存扫描，再 unpersist。事件日志确认发生实际 shuffle。

## 二、环境与数据

Spark 4.2.0 官方镜像固定 digest；local[2]，4 个输入和 shuffle 分区；显式禁用 AQE；2 CPU、3GiB，driver memory 1g，network=none。无 pip 安装。

## 三、步骤与断言

src/plan.py 先断言计划和结果，再保留事件日志；关闭 Spark 后提取 stage 与 TaskEnd shuffle 指标。全部断言随 spark-submit 返回状态。

## 四、证据

plans.txt、cache-plan.txt 为完整计划；events/ 为完整事件日志；stages.json 为 StageCompleted 中提取的原始 stage 信息；results.json 和 stdout.txt 为断言摘要；environment.txt 为环境。

## 五、限制与不能推出的结论

不代表多机吞吐、扩展性、跨网带宽、生产数据分布或加盐一定更快。shuffle_write_bytes 累加整个脚本的多个 action，不能归于单条 SQL。字节数、stage id 和时序可能变化，不断言固定字节/耗时。分区计数只适用于该固定数据、版本和哈希配置。

## 六、复现与清理

`make verify` 运行并断言，结果进入 build/run；`make evidence` 重新运行并采集 evidence；`make clean` 删除本实验的构建文件。容器均随结束清理；网络实验还会删除自己的 compose 网络。不自动删除可复用镜像或共享依赖缓存。

## 七、验证历史

- 2026-10-19：首次验证。先完成程序断言，再按观察写正文；未进行多机性能或生产验证。
- 2026-10-20：review 后移除与 Spark 无关的虚构特征时间示例；补脚本头部说明。
