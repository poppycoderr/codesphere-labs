# 验证记录：同一串位的几种解释

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| (byte) 0xFF | -1；与 0xFF 比较为 false |
| 字节 80 FF 10 01 累加 | 有符号 -112、无符号 400，低 8 位都是 0x90；有符号和 % 256 = -112 |
| 拼接 01 F0 | 不屏蔽得 -16，屏蔽后 496 |
| (byte) (0xF0 >>> 4) | -1；先 & 0xFF 得 15 |
| 8 位 7F+01 / FF+01 / 80+80 | 溢出不进位 / 进位不溢出 / 进位且溢出 |
| 无符号 4294967280 + 32 | 结果 16，compareUnsigned 判出进位；Math.addExact 不报错 |
| -MIN_VALUE、MIN_VALUE / -1 | 都是 MIN_VALUE；negateExact 抛异常 |
| 0xFFFFFFFF 的比较、除以 2、打印、解析 | 有符号 -1/0/-1/parseInt 抛异常；无符号 1/2147483647/4294967295/parseUnsignedInt 成功 |
| 相减比较器排序 | [0, 5, MAX, MIN, -1] |
| 计时器越过 Long.MAX_VALUE | t1 > t0 为 false，t1 - t0 > 0 为 true |
| 序号相差 2^31 + 1 | 相减判为更早 |
| (int) 3000000000L | -1294967296；toIntExact 抛异常 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只覆盖 JDK 25 的整数语义；这些行为由 Java 语言规范第 4.2、5.1、15.17—15.19 节规定，与 JDK 版本无关。
- 进位与溢出标志是按 8 位算术用 int 模拟的，Java 本身不暴露 CPU 的标志位。
- 相减比较器排序的具体顺序取决于 List.sort 的实现；元素更多时 TimSort 可能抛出「违反比较约定」的异常。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-28 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
