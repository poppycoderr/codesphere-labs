#!/usr/bin/env bash
# @Bean 方法调用、FactoryBean、registerSingleton 与同名覆盖（Spring Boot 4.1.2 / Framework 7.0.10）
# 用法：scripts/verify.sh [输出目录]，默认 target/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh

OUT="${1:-target/run}"
require_java 21
require mvn python3 
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
rm -f target/facts.tsv

log "运行 JUnit 测试（mvn test）"
mvn -B -q -Dstyle.color=never test >"$OUT/maven-test.log" 2>&1 || { cat "$OUT/maven-test.log"; fail "测试失败"; }
python3 ../../shared/scripts/surefire-summary.py target/surefire-reports "$OUT/test-results.txt"
sort target/facts.tsv >"$OUT/facts.tsv"
mvn -B -q -Dstyle.color=never dependency:list -DincludeScope=runtime -DoutputFile="$PWD/$OUT/dependencies.txt" >/dev/null
sed -i.bak -E 's/ -- module .*//' "$OUT/dependencies.txt" && rm -f "$OUT/dependencies.txt.bak"
write_environment "$OUT/environment.txt" "maven: $(mvn -v | head -1 | awk '{print $3}')"
normalize_paths <"$OUT/maven-test.log" | sed -E 's/^[0-9-]+T[0-9:.+-]+ +/<timestamp> /' >"$OUT/maven-test.log.tmp" && mv "$OUT/maven-test.log.tmp" "$OUT/maven-test.log"

f="$OUT/facts.tsv"
expect_line "$OUT/test-results.txt" "tests=6 failures=0" "6 个测试全部通过"
expect_line "$f" "写在 @Component 类里的 @Bean 方法互相调用：orders 里的 Clock 与容器里的是同一个=false；Clock 构造 2 次" "config.component"
expect_line "$f" "@Configuration（默认 full）里 @Bean 方法互相调用：orders 里的 Clock 与容器里的是同一个=true，billing 里的 Clock 与容器里的是同一个=true；Clock 构造 1 次" "config.full"
expect_line "$f" "@Configuration(proxyBeanMethods = false) 里同样的调用：orders 里的 Clock 与容器里的是同一个=false，billing 里的 Clock 与容器里的是同一个=false；Clock 构造 3 次" "config.lite"
expect_line "$f" "容器启动时对工厂的调用：工厂构造、getObjectType、getObjectType、getObjectType；之后取两次产品：getObject、getObject" "factory.calls"
expect_line "$f" "getBean(\"connection\") 得到 Connection，getBean(\"&connection\") 得到 ConnectionFactoryBean；isSingleton=false 时两次取产品是同一个=false" "factory.names"
expect_line "$f" "同样两个配置类用 Spring Boot 启动：启动失败：BeanDefinitionOverrideException" "override.boot"
expect_line "$f" "直接用 Spring Framework 的容器，两个配置类都定义了名为 dataSource 的 Bean：启动成功，取到的是「测试库」" "override.framework"
expect_line "$f" "容器关闭后的回调记录：registerBean 的对象 初始化、registerBean 的对象 销毁（registerSingleton 交进去的现成对象既不初始化也不销毁）" "register.callbacks"
expect_line "$OUT/dependencies.txt" "org.springframework.boot:spring-boot:jar:4.1.2:compile" "spring-boot 4.1.2"
expect_line "$OUT/dependencies.txt" "org.springframework:spring-context:jar:7.0.10:compile" "spring-context 7.0.10"
log "全部通过，输出在 $OUT"
