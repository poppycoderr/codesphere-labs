# Spring MVC 请求经过的各层

对应文章：[mvc-request-layers.md](https://github.com/poppycoderr/codesphere/blob/master/docs/spring/mvc-request-layers.md)。

Spring Boot 4.1.2 + 内嵌 Tomcat，测试用随机端口与 JDK HttpClient 发真实请求。一个 Filter 与一个 HandlerInterceptor 在调用前后各加一个响应头并记录响应是否已提交，配合几个端点观察各层的顺序与可见性。

```bash
make verify     # 需要 JDK 21+、Maven
make evidence   # 重新采集 evidence/
make clean
```
