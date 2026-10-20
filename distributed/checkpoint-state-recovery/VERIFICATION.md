# Flink checkpoint 后的单机算子状态恢复：验证记录

## 一、结论

DataGeneratorSource 生成 1—200，首次在 120 前故意失败；重启后的第一条记录读到非零 ValueState，且恢复值正好等于该记录序号减 1（状态与数据源位置来自同一个 checkpoint），最终计数 200。恢复点随 checkpoint 时序变化（已观测到 115—119），只断言两者的关系，不断言固定编号。

## 二、环境与数据

Flink 2.3.0；flink-connector-datagen 2.3.0；JDK 21.0.12 固定 digest；Maven 固定 digest，POM 中插件与依赖固定版本。单机 local environment，并行度 1，network=none，2 CPU、2GiB。

## 三、步骤与断言

Maven 编译并复制依赖；JDK 21 在一个容器里启动本地环境，100ms checkpoint 和最多两次固定延迟重启；检查第一次状态为零、恢复状态大于零且等于恢复后第一条输入减 1、最终计数 200；Flink 与 JDK 版本由程序运行时读取。120 秒超时只防挂起，不作为性能阈值。

## 四、证据

attempts.txt 是每次执行第一条记录及恢复值的原始观测；results.txt 是程序输出与最终计数断言；environment.txt 为环境。

## 五、限制与不能推出的结论

未终止 TaskManager 或 JobManager 进程；没有多机、外部 durable checkpoint 存储、外部事务 sink 或 HTTP 输出。不能推出集群高可用、端到端恰好一次或恢复耗时承诺。观测文件是验证用副作用，不是恰好一次 sink。

## 六、复现与清理

`make verify` 运行并断言，结果进入 build/run；`make evidence` 重新运行并采集 evidence；`make clean` 删除本实验的构建文件。容器均随结束清理；网络实验还会删除自己的 compose 网络。不自动删除可复用镜像或共享依赖缓存。

## 七、验证历史

- 2026-10-19：首次验证。先完成程序断言，再按观察写正文；未进行多机性能或生产验证。
- 2026-10-20：review 后加强断言：恢复值 = 恢复后第一条输入 − 1；Flink 与 JDK 版本改为运行时读取。
