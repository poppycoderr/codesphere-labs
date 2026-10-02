#!/usr/bin/env bash
# asyncio 任务的生命周期：gather 与 TaskGroup 的失败处理、取消是请求、wait_for 与吞掉取消、丢失引用的任务、无人等待的异常、阻塞调用、跨 await 的共享状态
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python 3.14.8 容器，只用标准库；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$PY" python src/lifecycle.py >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY"
cat "$f" >&2
expect_line "$f" "env	python=3.14.8" "运行在 Python 3.14.8"
expect_line "$f" "gather.default	gather 中一个任务 50ms 后失败：gather 抛出 ValueError；另外两个任务 仍在运行、仍在运行" "gather 抛出异常后其余任务仍在运行"
expect_line "$f" "gather.return_exceptions	return_exceptions=True：等了 200ms 全部结束后返回 ['done', ValueError, 'done']" "return_exceptions 等全部结束并把异常作为结果"
expect_regex "$f" "^taskgroup	.*抛出 ExceptionGroup，内含 \['ValueError'\]；离开 async with 时另外两个任务 已停止（收到 CancelledError 后退出）、已停止（收到 CancelledError 后退出），finally 已执行=True$" "TaskGroup 取消兄弟任务并等它们结束"
expect_line "$f" "cancel.requested	cancel() 刚返回时：task.done()=False，task.cancelled()=False" "cancel 只是提出请求"
expect_line "$f" "cancel.normal	让出事件循环之后：task.cancelled()=True，已停止（收到 CancelledError 后退出），finally 已执行=True" "下一次 await 处收到 CancelledError"
expect_line "$f" "cancel.swallowed	协程捕获 CancelledError 后继续循环：cancel() 之后 仍在运行；最终 task.cancelled()=False，结果 'done'" "吞掉 CancelledError：取消无效"
expect_regex "$f" "^wait_for\.normal	wait_for\(…, 0\.05\)：抛出 TimeoutError，用时约 [5-7]0ms；任务 已停止（收到 CancelledError 后退出）$" "wait_for 超时会取消内部任务"
expect_line "$f" "wait_for.swallowed	协程吞掉取消时 wait_for(…, 0.05)：正常返回 'done'，用时约 400ms（任务自己要跑 400ms）" "协程吞掉取消：wait_for 的超时不起作用"
expect_line "$f" "timeout.context	async with asyncio.timeout(0.05)：抛出 TimeoutError；任务 已停止（收到 CancelledError 后退出）" "asyncio.timeout 同样通过取消实现"
expect_line "$f" "lost.no_reference	不保存 create_task 的返回值，垃圾回收之后：asyncio 日志 'Task was destroyed but it is pending!'" "没有引用的任务会在执行中途被回收"
expect_line "$f" "lost.kept_reference	保存了引用，垃圾回收之后：任务 done()=False，asyncio 日志 '（无）'" "保存引用后不会被回收"
expect_line "$f" "lost.exception	后台任务抛出异常且无人等待：引用还在时 asyncio 日志 '（无）'；引用释放后日志首行 'Task exception was never retrieved'" "无人等待的异常只在任务对象销毁时才报告"
expect_line "$f" "blocking.direct	协程里调用 time.sleep(0.3)：每 10ms 一次的心跳，最大间隔 不少于 300ms" "阻塞调用停住整个事件循环"
expect_line "$f" "blocking.to_thread	协程里调用 asyncio.to_thread(time.sleep, 0.3)：每 10ms 一次的心跳，最大间隔 小于 100ms" "to_thread 不阻塞事件循环"
expect_line "$f" "race.with_await	100 个协程各加 1，读和写之间有 await：结果 1" "跨 await 的读改写会丢失更新"
expect_line "$f" "race.no_await	100 个协程各加 1，读和写之间没有 await：结果 100" "不跨 await 时不会交错"
log "全部通过，输出在 $OUT"
