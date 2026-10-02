# 许可、资源与租约

对应文章：[resource-lease-ownership.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/resource-lease-ownership.md)。

`src/LeaseLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，全部场景单线程顺序执行，输出是确定的，`scripts/verify.sh` 把它与预期逐行比较。

1. 2 个许可、池里是同一个对象放了两次；
2. `tryAcquire` 超时后仍在 `finally` 里 `release`；
3. 借出后抛异常、没有归还；
4. 同一个资源归还两次；
5. 归还后继续使用手里的引用；
6. 损坏的资源原样归还；
7. 带租约的池：资源各不相同、租约只能关闭一次、关闭后取资源抛异常、`try`-with-resources 遇到异常仍归还、损坏的资源被替换。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```
