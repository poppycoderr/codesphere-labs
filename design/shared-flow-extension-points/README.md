# 多个产品共用一套流程：骨架、扩展点与契约测试

对应文章：[shared-flow-extension-points.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/shared-flow-extension-points.md)。

`src/PlatformLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，所有输入写在源码里。课程、会员、实物等产品共用一套下单流程，三种写法：共用流程里按产品 `if` 分支（以源码文本形式统计分支数）；每一步都可以被替换的流水线；平台持有骨架方法、风控与支付不是扩展点、产品只实现 `prepare`、`fee`、`fulfil` 三个扩展点。最后对所有注册的扩展跑同一组手续费契约检查。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
