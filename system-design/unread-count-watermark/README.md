# 未读数的两种存法

对应文章：[unread-count-watermark.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/unread-count-watermark.md)。

`src/UnreadLab.java` 通过 JDBC 连接共享的 MySQL 8.4.11 容器（默认隔离级别 REPEATABLE READ），用两条连接按固定顺序交替执行，所以每种交错都是确定的。

两种存法：

- **计数器**：`unread_counter(user_id, conv_id, unread)`，每来一条消息给接收方加一，用户读了就清零。
- **已读位置**：`read_watermark(user_id, conv_id, last_read_id)`，只记读到哪一条，未读数用 `COUNT(*) … WHERE id > last_read_id AND sender <> 自己` 现算。

场景：通知重复投递；清零请求与新消息交错；多端上报乱序；自己发的消息；两个事务的自增 ID 顺序与提交顺序相反；事务回滚留下的 ID 空洞；按会话加行锁分配序号；千人群的写入行数；只数到 100 的封顶计数。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟（不含拉取镜像）。结束后 `make clean` 删除容器
make evidence
make clean
```
