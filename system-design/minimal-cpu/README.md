# 一台最小的累加器 CPU

对应文章：[minimal-cpu.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/minimal-cpu.md)。

`src/cpu.py` 是约 150 行的模拟器：8 位累加器、程序计数器、Z/C 标志、256 字节内存，两字节指令（操作码 + 地址），`step` 是一个时钟沿——只读旧状态、返回新状态。`Contract` 收拢两条可选约定：LDA 是否更新 Z、非法操作码停机还是跳过。另有两个对照实现：逐句赋值的 `NaiveCPU` 与写回延后一拍的 `LatchedCPU`。`src/lab.py` 依次运行乘法程序、交换指令、标志位约定、停机前提交、漏写 HLT 五组场景；`tests/test_cpu.py` 是单元测试。

```bash
make verify     # 需要 Docker；约 10 秒
make evidence   # 重新采集 evidence/
make clean
```
