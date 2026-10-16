# 计划、发生与记录三个时刻

对应文章：[planned-occurred-recorded.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/planned-occurred-recorded.md)。

`src/TimeLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，所有时间都是写在源码里的固定值，输出确定。五个场景：用审计字段 `updatedAt` 代替支付时间；周期提醒的计划时刻存成第一次算出的 `Instant` 每天加 24 小时，与存「本地时间加时区」每次重新计算；设备离线后补传的读数按收到日期与按发生日期汇总；取消请求按收到时间与按提出时间判断先后；计划任务的计划时刻与实际执行时刻之差。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
