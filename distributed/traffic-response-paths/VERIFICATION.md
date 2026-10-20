# 分流位置、地址可见性与非幂等重试：验证记录

## 一、结论

urllib 对 302 POST 改为 GET 并丢掉请求体；不自动跟随 307 POST。HTTP 和 TCP 全代理的 socket 对端都是代理，HTTP 入口明确写入 X-Forwarded-For。Nginx 的 http_503 配置下，只有显式允许 non_idempotent 才将已发出的 POST 重试到备用端。权威答案变化后，5 秒 TTL 缓存仍短暂返回旧地址，再过期更新。

## 二、环境与数据

Nginx 1.30.0；HAProxy 3.4.2；CoreDNS 1.14.7；CPython 3.14.8。compose.yaml 全部镜像锁定 digest，无额外 Python 包。Docker 网络 internal=true，没有宿主机端口映射；每个服务限制 CPU 与内存。

## 三、步骤与断言

compose 启动六个本地服务；src/check.py 有界等待就绪，再验证各路径；EXIT trap 无论成功失败都清理本 compose 项目的容器和网络。

## 四、证据

results.json 包含实际对端地址、请求方法和请求体、各后端收到的次数及 DNS 前后答案；images.txt 保存固定镜像列表；versions.txt 是在容器内由各程序输出的版本；environment.txt 为环境。

## 五、限制与不能推出的结论

只有本机 Docker 网络；没有 LVS、NAT/DR 实验或吞吐测试。权威 DNS 是实验专用最小应答器，只用于 A 查询，不是生产服务。示例地址没有被连接，不模拟真实故障节点。302/307 自动跟随结果仅代表 urllib，不代表浏览器。代理接收请求的计数不证明业务副作用是否发生。IP 和启动查询次数随运行变化。

## 六、复现与清理

`make verify` 运行并断言，结果进入 build/run；`make evidence` 重新运行并采集 evidence；`make clean` 删除本实验的构建文件。容器均随结束清理；网络实验还会删除自己的 compose 网络。不自动删除可复用镜像或共享依赖缓存。

## 七、验证历史

- 2026-10-19：首次验证。先完成程序断言，再按观察写正文；未进行多机性能或生产验证。
- 2026-10-20：review 后补充容器内读取的组件版本（versions.txt）与备用后端收到相同请求体的断言；运行前清空输出目录。
