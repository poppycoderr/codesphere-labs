#!/usr/bin/env bash
# Running 不等于可用：没有就绪探针时的滚动发布、就绪探针与 preStop、新版本永远不就绪时的卡住与回滚、探针检查下游依赖的放大效应
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：Docker；创建名为 csl-pr 的 kind 集群（Kubernetes 1.36.4），结束时删除；约 12 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=csl-pr
f="$OUT/output.tsv"; : >"$f"
out() { printf '%s\t%s\n' "$1" "$2" >>"$f"; }
now() { python3 -c 'import time; print(f"{time.time():.1f}")'; }
since() { python3 -c "print(f'{$(now) - $1:.1f}')"; }
k() { kctl "$C" "$@"; }
m() { python3 scripts/manifest.py "$@"; }
trap 'kind_down "$C" >/dev/null 2>&1 || true' EXIT

kind_up "$C"
k wait --for=condition=Ready node --all --timeout=180s >/dev/null
docker exec "$C-control-plane" mkdir -p /opt/lab
docker cp src "$C-control-plane:/opt/lab/src" >/dev/null
k create configmap code --from-file=/opt/lab/src >/dev/null
m service | k apply -f - >/dev/null

# deploy <参数...>：替换 web 为一组全新的 v1 Pod 并等它们全部就绪
deploy() {
  k delete deployment web --ignore-not-found --wait >/dev/null
  k wait --for=delete pod -l app=web --timeout=120s >/dev/null 2>&1 || true
  m deployment "$@" | k apply -f - >/dev/null
  k rollout status deploy/web --timeout=180s >/dev/null
  sleep 12                                         # 没有就绪探针时 rollout 立刻完成，等预热结束再开始测量
}
# client_start <名字> <秒>：启动压测客户端并等它开始发请求
client_start() { m client --name "$1" --duration "$2" | k apply -f - >/dev/null; k wait --for=condition=Ready "pod/$1" --timeout=120s >/dev/null; sleep 2; }
client_result() {
  k wait --for=jsonpath='{.status.phase}'=Succeeded "pod/$1" --timeout=300s >/dev/null
  k logs "$1" | sed -n 's/^RESULT //p'
}
# pods_of <版本>：未处于删除中的 Pod 的 phase/ready
pods_of() { k get pods -l "app=web,ver=$1" -o json | python3 scripts/pods.py; }
ready_endpoints() { k get endpointslice -l kubernetes.io/service-name=web -o jsonpath='{range .items[*].endpoints[*]}{.conditions.ready}{"\n"}{end}' | grep -c true || true; }
restarts() { k get pods -l app=web -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount} {end}'; }

# 1. 没有就绪探针：新 Pod 一启动就被当作可用，旧 Pod 随即被删，预热期间的请求被拒绝
common=(--env START_DELAY=10 --prestop 3)
deploy "${common[@]}" --env VERSION=v1
client_start c1 45
t=$(now); m deployment "${common[@]}" --env VERSION=v2 --label v2 | k apply -f - >/dev/null
sleep 3
out noprobe.pods_3s "发布 3 秒后 v2 Pod（phase/ready）：$(pods_of v2)；日志末行：$(k logs -l app=web,ver=v2 --tail=1 | sort -u | tr '\n' ' ')"
k rollout status deploy/web --timeout=180s >/dev/null
out noprobe.rollout "rollout status 返回耗时 $(since "$t") 秒"
out noprobe.client "$(client_result c1)"

# 2. 就绪探针 + preStop：预热完成才接流量，旧 Pod 先从 Service 摘掉再退出
common=(--env START_DELAY=10 --prestop 3 --readiness /ready)
deploy "${common[@]}" --env VERSION=v1
client_start c2 60
t=$(now); m deployment "${common[@]}" --env VERSION=v2 --label v2 | k apply -f - >/dev/null
sleep 3
out readiness.pods_3s "发布 3 秒后 v2 Pod（phase/ready）：$(pods_of v2)"
k rollout status deploy/web --timeout=180s >/dev/null
out readiness.rollout "rollout status 返回耗时 $(since "$t") 秒"
out readiness.client "$(client_result c2)"

# 3. 有就绪探针但没有 preStop：进程收到 SIGTERM 立即退出，摘除流量的传播还没完成
common=(--env START_DELAY=10 --readiness /ready)
deploy "${common[@]}" --env VERSION=v1
client_start c3 60
m deployment "${common[@]}" --env VERSION=v2 --label v2 | k apply -f - >/dev/null
k rollout status deploy/web --timeout=180s >/dev/null
out noprestop.client "$(client_result c3)"

# 4. 新版本缺配置、永远不就绪：maxUnavailable=0 时卡住但不损失容量，超过 progressDeadlineSeconds 后标记失败，undo 回到旧版本
common=(--env START_DELAY=2 --prestop 3 --readiness /ready --surge 1 --unavailable 0 --deadline 20)
deploy "${common[@]}" --env VERSION=v2 --label v2
client_start c4 50
m deployment "${common[@]}" --env VERSION=v3 --env REQUIRE_DB_URL=true --label v3 | k apply -f - >/dev/null
if k rollout status deploy/web --timeout=60s >"$OUT/stuck-rollout-status.txt" 2>&1; then out stuck.status "rollout status 成功"; else out stuck.status "rollout status 失败：$(tail -1 "$OUT/stuck-rollout-status.txt")"; fi
out stuck.condition "Progressing 条件：$(k get deploy web -o jsonpath='{range .status.conditions[?(@.type=="Progressing")]}{.status} {.reason}{end}')"
v3pod=$(k get pods -l ver=v3 -o jsonpath='{.items[0].metadata.name}')
out stuck.pods "v2（phase/ready）：$(pods_of v2)；v3：$(pods_of v3)；v3 事件：$(k get events --field-selector "involvedObject.name=$v3pod,reason=Unhealthy" -o jsonpath='{range .items[*]}{.message}{"\n"}{end}' | grep -m1 'statuscode: 503')"
k get pods -l app=web >"$OUT/stuck-pods.txt"
out stuck.endpoints "Service 就绪端点数：$(ready_endpoints)"
t=$(now); k rollout undo deploy/web >/dev/null; k rollout status deploy/web --timeout=120s >/dev/null
k wait --for=delete pod -l ver=v3 --timeout=60s >/dev/null
out stuck.undo "undo 后到 v3 Pod 删除完毕耗时 $(since "$t") 秒；v2：$(pods_of v2)；v3：$(pods_of v3)"
out stuck.client "$(client_result c4)"

# 4b. 同样的坏版本，maxSurge=0、maxUnavailable=1：先删一个旧 Pod 再创建新 Pod，卡住期间只剩 2 个端点
common=(--env START_DELAY=2 --prestop 3 --readiness /ready --surge 0 --unavailable 1 --deadline 20)
deploy "${common[@]}" --env VERSION=v2 --label v2
m deployment "${common[@]}" --env VERSION=v3 --env REQUIRE_DB_URL=true --label v3 | k apply -f - >/dev/null
k rollout status deploy/web --timeout=60s >/dev/null 2>&1 || true
out stuck_unavail.pods "v2（phase/ready）：$(pods_of v2)；v3：$(pods_of v3)；Service 就绪端点数：$(ready_endpoints)"

# 5. 下游依赖故障：探针检查依赖 vs 探针只检查自身
m deployment --name dep --replicas 1 --env VERSION=dep --readiness /healthz | k apply -f - >/dev/null
m service --name dep | k apply -f - >/dev/null
k rollout status deploy/dep --timeout=120s >/dev/null
dep_case() {  # dep_case <键> <deploy 参数...>
  local key=$1; shift
  deploy --env VERSION=v2 --label v2 --env START_DELAY=10 --env DEP=http://dep/healthz --prestop 3 "$@"
  client_start "c-${key//_/-}" 40
  k scale deploy/dep --replicas 0 >/dev/null
  k wait --for=delete pod -l app=dep --timeout=60s >/dev/null 2>&1 || true
  sleep 30
  out "$key.down" "依赖停止 30 秒后：Service 就绪端点数 $(ready_endpoints)；各 Pod 重启次数 $(restarts)"
  out "$key.client" "$(client_result "c-${key//_/-}")"
  local t; t=$(now)
  k scale deploy/dep --replicas 1 >/dev/null; k rollout status deploy/dep --timeout=120s >/dev/null
  until [ "$(ready_endpoints)" = 3 ]; do sleep 1; done
  out "$key.recover" "依赖恢复后到 3 个端点全部就绪耗时 $(since "$t") 秒；各 Pod 重启次数 $(restarts)"
}
dep_case dep_probe --startup /healthz --readiness /ready-with-dep --liveness /ready-with-dep
dep_case local_probe --startup /healthz --readiness /ready --liveness /healthz

k version -o json | python3 -c 'import json,sys; v=json.load(sys.stdin); print("kubernetes_server:", v["serverVersion"]["gitVersion"])' >"$OUT/cluster-version.txt"
"$(kind_bin)" version >>"$OUT/cluster-version.txt"
write_environment "$OUT/environment.txt" "kind_node_image: $KIND_NODE_IMAGE" "app_image: $(sed -n 's/^PYTHON = "\(.*\)"$/\1/p' scripts/manifest.py)"
cat "$f" >&2
expect_regex "$f" "^noprobe\.pods_3s	.*Running/true Running/true Running/true ；日志末行：v2 starting" "没有就绪探针：还在预热、没有监听端口的 Pod 已经是 Running 且 ready"
expect_regex "$f" "^readiness\.pods_3s	.*：Running/false $" "有就绪探针：预热中的 Pod 是 Running 但未就绪"
expect_regex "$f" "^stuck\.status	rollout status 失败：.*exceeded its progress deadline" "坏版本：超过 progressDeadlineSeconds 后 rollout status 失败"
expect_line "$f" "stuck.condition	Progressing 条件：False ProgressDeadlineExceeded" "Deployment 条件标记为 ProgressDeadlineExceeded，但不会自动回滚"
expect_regex "$f" "^stuck\.pods	v2（phase/ready）：Running/true Running/true Running/true ；v3：Running/false ；v3 事件：Readiness probe failed: HTTP probe failed with statuscode: 503" "maxUnavailable=0：3 个旧 Pod 都在，新 Pod Running 但不就绪"
expect_line "$f" "stuck.endpoints	Service 就绪端点数：3" "卡住期间 Service 仍有 3 个端点"
expect_regex "$f" "^stuck\.undo	.*；v2：Running/true Running/true Running/true ；v3：$" "undo 后回到 3 个 v2，v3 Pod 被删除"
expect_regex "$f" "^stuck_unavail\.pods	v2（phase/ready）：Running/true Running/true ；v3：Running/false ；Service 就绪端点数：2$" "maxUnavailable=1：卡住期间只剩 2 个端点"
expect_regex "$f" "^dep_probe\.down	.*就绪端点数 0；" "探针检查依赖：依赖一停，所有端点被摘除"
expect_regex "$f" "^local_probe\.down	.*就绪端点数 3；各 Pod 重启次数 0 0 0 $" "探针只查自身：端点保留，不重启"
python3 - "$f" <<'PY'
import json, re, sys
t = open(sys.argv[1], encoding="utf-8").read()
g = lambda k: re.search(rf"^{re.escape(k)}\t(.*)$", t, re.M)[1]
r = {k: json.loads(g(k + ".client")) for k in ["noprobe", "readiness", "noprestop", "stuck", "dep_probe", "local_probe"]}
assert r["noprobe"]["fail"] > 0 and set(r["noprobe"]["reasons"]) == {"connection refused"}, r["noprobe"]
assert r["readiness"]["fail"] == 0, r["readiness"]
assert r["stuck"]["fail"] == 0 and set(r["stuck"]["versions"]) == {"v2"}, r["stuck"]
dr = r["dep_probe"]["reasons"]
assert dr.get("connection refused", 0) + dr.get("timeout", 0) > 0, r["dep_probe"]              # 端点全被摘除后，请求连 Pod 都到不了
assert set(r["local_probe"]["reasons"]) == {"http 503"}, r["local_probe"]
restarts = [int(x) for x in re.search(r"重启次数 ([\d ]+)", g("dep_probe.down"))[1].split()]
assert all(x > 0 for x in restarts), restarts                                          # 依赖停止期间每个 Pod 都被存活探针重启
rec = {k: float(re.search(r"耗时 ([\d.]+) 秒", g(k + ".recover"))[1]) for k in ["dep_probe", "local_probe"]}
assert rec["dep_probe"] > rec["local_probe"], rec
print(f"通过：无探针失败 {r['noprobe']['fail']} 次；依赖探针重启 {restarts}，恢复 {rec['dep_probe']} 秒 vs {rec['local_probe']} 秒")
PY
log "全部通过，输出在 $OUT"
