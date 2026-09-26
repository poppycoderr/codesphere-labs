#!/usr/bin/env bash
# Bean 生命周期：单例回调顺序、容器交出的是代理、@PostConstruct 里事务不生效、JDK 代理与按类型取 Bean、prototype 不销毁、提前创建的 Bean 错过代理
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
grep "not eligible for getting processed" "$OUT/maven-test.log" | head -1 >"$OUT/early-bean-warning.txt" || true
mvn -B -q -Dstyle.color=never dependency:list -DincludeScope=runtime -DoutputFile="$PWD/$OUT/dependencies.txt" >/dev/null
sed -i.bak -E 's/ -- module .*//' "$OUT/dependencies.txt" && rm -f "$OUT/dependencies.txt.bak"
write_environment "$OUT/environment.txt" "maven: $(mvn -v | head -1 | awk '{print $3}')"
normalize_paths <"$OUT/maven-test.log" | sed -E 's/^[A-Z][a-z]{2} [0-9]{1,2}, [0-9]{4} [0-9:]+ [AP]M /<timestamp> /' >"$OUT/maven-test.log.tmp" && mv "$OUT/maven-test.log.tmp" "$OUT/maven-test.log"

expect_line "$OUT/test-results.txt" "tests=5 failures=0" "5 个测试全部通过"
expect_line "$OUT/facts.tsv" "BeanFactoryPostProcessor 修改 priceCache 的定义：timeoutMs=500 → Clock 构造 → PriceCache 构造（注入 Clock） → 属性填充 timeoutMs=500 → BeanNameAware.setBeanName → BeanFactoryAware.setBeanFactory → ApplicationContextAware.setApplicationContext → BeanPostProcessor.before → @PostConstruct → InitializingBean.afterPropertiesSet → @Bean(initMethod) → BeanPostProcessor.after（拿到的是代理） → SmartInitializingSingleton.afterSingletonsInstantiated → SmartLifecycle.start → ContextRefreshedEvent" "启动时的回调顺序"
expect_line "$OUT/facts.tsv" "ContextClosedEvent → SmartLifecycle.stop → @PreDestroy → DisposableBean.destroy → @Bean(destroyMethod)" "关闭时的回调顺序"
expect_line "$OUT/facts.tsv" "与构造出的实例相同=false；@PostConstruct 里调用 @Transactional 方法：事务活跃=false；启动后经容器取得的对象调用：事务活跃=true；timeoutMs=500" "容器交出代理，@PostConstruct 里事务不生效"
expect_line "$OUT/facts.tsv" "priceCache 是 JDK 动态代理=true，按类型 getBean(PriceCache.class)：NoSuchBeanDefinitionException" "默认 JDK 代理下按实现类取不到 Bean"
expect_line "$OUT/facts.tsv" "取 2 次 prototype Bean 后关闭容器：@PostConstruct 2 次，@PreDestroy 0 次" "prototype 不调用销毁回调"
expect_line "$OUT/facts.tsv" "被后处理器提前创建的 AuditService：是代理=false，调用 @Transactional 方法时事务活跃=false" "提前创建的 Bean 错过代理"
expect_line "$OUT/early-bean-warning.txt" "Bean 'auditService' of type [labs.lifecycle.EarlyConfig\$AuditService] is not eligible for getting processed by all BeanPostProcessors" "容器打印了警告"
expect_line "$OUT/dependencies.txt" "org.springframework:spring-context:jar:7.0.9" "spring-context 7.0.9"
log "全部通过，输出在 $OUT"
