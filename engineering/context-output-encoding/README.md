# 同一份数据在不同浏览器上下文的输出边界

对应文章：[docs/engineering/context-output-encoding.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/context-output-encoding.md)。

普通正文、带引号属性与 JavaScript 内联的浏览器值均与原文相同；误用 HTML 插值的脚本值不同；无引号属性被普通空格拆开；属性编码没有过滤 URL 协议。CSP 阻止无 nonce 的脚本，允许正确 nonce 的无害脚本。HttpOnly 对脚本不可见，但同源请求仍携带；Strict 与 Lax 在跨站顶层 GET 下的发送不同。

## 运行

```bash
make verify
make evidence
make clean
```

需要 Docker；首次拉取和依赖下载耗时不固定。脚本只使用本实验的文件和容器，不修改宿主机磁盘或外部服务。环境、证据与限制见 [VERIFICATION.md](VERIFICATION.md)。
