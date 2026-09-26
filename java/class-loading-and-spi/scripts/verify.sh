#!/usr/bin/env bash
# 类加载：同名类不同加载器、初始化时机与准备阶段零值、ServiceLoader 与上下文类加载器、父优先与子优先、类加载器回收、版本不一致
# 用法：scripts/verify.sh [输出目录]，默认 build/run；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21
java src/ClassLoading.java build/tmp >"$OUT/output.tsv"
write_environment "$OUT/environment.txt"
f="$OUT/output.tsv"
expect_line "$f" "名称相同=true，Class 对象相同=false" "同名类由不同加载器定义，是两个类型"
expect_line "$f" "class com.example.Plugin cannot be cast to class com.example.Plugin (com.example.Plugin is in unnamed module of loader 'plugin-b'" "ClassCastException 报出两个加载器"
python3 - "$f" <<'PY'
import sys
lines = [l.split("\t", 1)[1] for l in open(sys.argv[1], encoding="utf-8").read().splitlines() if l.startswith("init")]
expected = ["读取编译期常量 Config.CONSTANT=7", "创建 Config[3] 数组，长度 3", "Class.forName(name, false, loader) 得到 Config",
            "Config 的静态初始化执行了；early=0，late=10", "读取 Config.BOXED=8"]
assert lines == expected, lines
print("通过：常量、数组、forName(false) 都不触发初始化；读取 Integer 常量才触发，early 读到准备阶段的零值")
PY
expect_line "$f" "上下文类加载器为父加载器时找到 0 个，为子加载器时找到 1 个；用接口自己的加载器查找找到 0 个" "SPI 依赖上下文类加载器"
expect_line "$f" "工作线程的上下文类加载器仍是 app" "线程池工作线程保留创建时的上下文类加载器"
expect_line "$f" "父优先得到 1.0，子优先得到 2.0" "父优先与子优先"
expect_line "$f" "SecurityException: Prohibited package name: java.lang" "不能在 java.* 包里定义类"
expect_line "$f" "丢弃加载器与实例后：加载器已被回收" "没有引用时加载器可以回收"
expect_line "$f" "实例登记在父加载器的静态列表里：加载器仍然存活" "父加载器的静态引用留住插件加载器"
expect_line "$f" "实例放进长期存活线程的 ThreadLocal：加载器仍然存活" "ThreadLocal 留住插件加载器"
expect_line "$f" "在同一线程里 remove 之后：加载器已被回收" "remove 之后可以回收"
expect_line "$f" "NoSuchMethodError: 'java.lang.String api.Client.call(int)'" "编译期与运行期版本不一致"
log "全部通过，输出在 $OUT"
