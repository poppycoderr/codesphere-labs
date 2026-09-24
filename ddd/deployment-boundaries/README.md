# 从模型到部署单元

对应文章：[boundaries-from-model-to-deployment.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/boundaries-from-model-to-deployment.md)。

报名上下文确认报名后通知参会人。通知上下文只通过 `notification.api` 暴露 `NotificationPort` 与 `ConfirmationNotice`（发布语言），`notification.internal` 是它的实现。部署方式放在 `deployment` 包：

- `InProcessNotification`：模块化单体，同一进程内的方法调用；
- `NotificationHttpServer` + `HttpNotificationClient`：通知拆成独立的 HTTP 服务（JDK 自带的 `HttpServer`，本机回环网络），客户端带超时、有限次重试和 `X-Trace-Id`。

三组测试：

- `ModuleBoundaryTest`：报名模块不能依赖通知的 `internal` 包，也不能依赖 `deployment` 包；
- `SameRulesBothDeploymentsTest`：同一组 3 个报名用例在两种部署下运行；
- `SplitFailureModesTest`：通知服务停止、超时后对方仍在处理、网络调用的额外延迟、traceId 跨进程传递。

报名用例在「提交」前同步调用通知，失败时报名一起回滚。这是为了展示拆分之后这种写法会怎样失效；把通知移到提交之后并可靠投递，见 [context-integration-outbox](../context-integration-outbox/)。

## 快速运行

```bash
make verify     # 需要 JDK 21、Maven；约 15 秒
make evidence
make clean
```
