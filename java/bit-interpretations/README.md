# 同一串位的几种解释

对应文章：[bit-interpretations.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/bit-interpretations.md)。

`src/Lab.java`（单文件，只用 JDK）依次输出：字节的两种读法与累加校验和；拼接大端 16 位数、`ByteBuffer.getShort`、对 byte 做 `>>>`、移位数的截断与字节序；8 位加法的进位与溢出标志、`Math.addExact` 与无符号进位判断、最小负数取反；`Integer` 的无符号比较、除法、打印与解析；相减比较器排序与越过最大值的计时器、序号；`long` 到 `int` 等缩窄转换。

```bash
make verify     # 需要 Docker；约 10 秒
make evidence   # 重新采集 evidence/
make clean
```
