# 检错与纠错的边界

对应文章：[error-detection-codes.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/error-detection-codes.md)。

`src/CodeLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，全部场景是对错误图样的穷举，输出是确定的，`scripts/verify.sh` 把它与预期逐行比较。

1. 奇偶位：9 位码字上 1、2、3 位翻转各发现多少；
2. 16 位反码求和（RFC 1071 的算法）：两个字交换位置、同一列一加一减；
3. CRC-8（多项式 0x07）：32 位码字上按错误位数穷举、按突发长度穷举、一个 24 位窗口内的全部图样；
4. CRC-32 在等长消息上的线性关系，同样的关系对 HMAC-SHA256 不成立；
5. Hamming(7,4)：全部 1 位错与 2 位错的译码结果；
6. 扩展 Hamming(8,4)：1—4 位错的译码结果。

## 快速运行

```bash
make verify     # 需要 Docker；约 20 秒
make evidence
make clean
```
