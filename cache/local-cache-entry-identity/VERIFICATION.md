# 验证记录：进程内缓存的条目身份

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 写入后登记过期任务，任务到点按 key 删除；期间已写入新值 | 新值被删，缓存为 null；remove(key, entry) 保留新值 |
| 用户重连后旧连接的关闭回调按用户删除 | 新连接被标成离线；remove(user, channel-1) 不删 |
| 按命中次数淘汰，降序排序后删除第一个 | 删掉了最热的「首页」；升序删「冷门」 |
| 四个线程同时未命中，各自 get 后回源 | 回源 4 次；computeIfAbsent 1 次 |
| put 后同步调用监听器，监听器抛错 | 调用方看到失败，值已写入 |
| 返回内部可变对象，调用方改了字段 | 下一个读者看到被改的值 |
| Caffeine expireAfterWrite 10s，5 秒时覆盖写，11 秒时读 | 读到新值 |
| Caffeine 四线程并发 get(key, loader) | 回源 1 次 |
| Caffeine 移除监听器抛错 | invalidate 正常返回，条目已移除（异常被记录日志） |
| Caffeine 缓存可变对象 | 同样被调用方改掉 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 手写缓存是为暴露问题刻意简化的写法，不代表某个具体库。
- 并发场景用闩锁或加载耗时固定交错，只说明可能的回源次数，不测量概率与性能。
- Caffeine 的行为对应 3.3.1 与本实验的配置（同步执行器、可控时钟）；默认配置下移除监听器在 ForkJoinPool 中异步执行。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-27 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
