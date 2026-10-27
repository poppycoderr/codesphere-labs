#!/bin/sh
# 在容器里：安装依赖、运行 JS 部分、分别以两种装饰器模式编译并运行同一段代码
set -e
npm ci --no-audit --no-fund --loglevel=error >/dev/null 2>&1
node src/run.mjs
echo "tsc	$(npx tsc --version)"
npx tsc --target es2022 --module nodenext --outDir build/std src/ts/standard.ts && node build/std/standard.js | sed 's/^/decorator./'
npx tsc --target es2022 --module nodenext --experimentalDecorators --outDir build/leg src/ts/legacy.ts && node build/leg/legacy.js | sed 's/^/decorator./'
codes=$(npx tsc --target es2022 --module nodenext --outDir build/mix src/ts/legacy.ts 2>&1 | grep -o 'error TS[0-9]*' | sort -u | tr '\n' ' ' || true)
echo "decorator.mixed	旧签名的装饰器不加 --experimentalDecorators 编译：${codes% }"
if node build/mix/legacy.js >/dev/null 2>build/mix.err; then echo "decorator.mixed-run	照样产出的 JS 运行成功"; else echo "decorator.mixed-run	照样产出的 JS 运行时 $(grep -o '^[A-Za-z]*Error[^)]*' build/mix.err | head -1)"; fi
