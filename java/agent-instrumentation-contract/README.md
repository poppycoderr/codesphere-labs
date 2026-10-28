# 插桩不能改变业务结果

对应文章：[agent-instrumentation-contract.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/agent-instrumentation-contract.md)。

`run.sh` 在容器里编译 `src/`、打出只含 `agent` 包的 agent jar（`Premain-Class` 与 `Can-Retransform-Classes`），再以 `-javaagent` 启动 `lab.Main`。`Agent` 只保存 `Instrumentation`，`Main` 按场景给 `lab.biz.Biz` 里各自独立的业务类安装 `src/agent/Advices.java` 中的通知，比较业务结果与采集结果：通知抛异常、方法以异常结束、基本类型返回值与构造器、已加载类的 retransform 与还原、加字段、重复转换、参数截断与遮挡、上报队列满。

```bash
make verify     # 需要 Docker；约 15 秒
make evidence   # 重新采集 evidence/
make clean
```
