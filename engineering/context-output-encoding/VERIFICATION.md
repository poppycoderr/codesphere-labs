# 同一份数据在不同浏览器上下文的输出边界：验证记录

## 一、结论

普通正文、带引号属性与 JavaScript 内联的浏览器值均与原文相同；误用 HTML 插值的脚本值不同；无引号属性被普通空格拆开；属性编码没有过滤 URL 协议。CSP 阻止无 nonce 的脚本，允许正确 nonce 的无害脚本。HttpOnly 对脚本不可见，但同源请求仍携带；Strict 与 Lax 在跨站顶层 GET 下的发送不同。

## 二、环境与数据

Thymeleaf 3.1.5.RELEASE；JDK 25.0.4.1；Playwright 1.63.0；Chromium 153.0.8010.12。Maven 和浏览器镜像均锁定 digest，Python 直接及传递依赖固定在 requirements.txt；Java 依赖由固定版本的 POM 解析。

## 三、步骤与断言

src/main/java/Render.java 先生成模板；src/browser.py 启动本地服务，依次断言数据、CSP 和 Cookie；任何断言失败返回非零。

## 四、证据

results.json 是浏览器和服务端观测与断言；template.txt 是渲染器版本；rendered.html 是模板实际渲染结果。environment.txt 保存运行环境。

## 五、限制与不能推出的结论

仅本机容器内测试无害文本和应用自有页面。没有导航特殊协议链接，没有外部目标、信息收集或攻击载荷。没有验证 CSS 编码、富文本清洗、跨浏览器差异、第三方 Cookie 策略或整站安全性。nonce 每响应生成，未归档实际值。2 CPU、2GiB 容器限制。

## 六、复现与清理

`make verify` 运行并断言，结果进入 build/run；`make evidence` 重新运行并采集 evidence；`make clean` 删除本实验的构建文件。容器均随结束清理；网络实验还会删除自己的 compose 网络。不自动删除可复用镜像或共享依赖缓存。

## 七、验证历史

- 2026-10-19：首次验证。先完成程序断言，再按观察写正文；未进行多机性能或生产验证。
- 2026-10-20：review 后改为运行时读取 Thymeleaf 版本，并断言 Thymeleaf、JDK 与 Chromium 版本；运行前清空输出目录。
