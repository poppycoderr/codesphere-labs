#!/usr/bin/env bash
# 声明式 API 背后的控制循环：改小 ReplicaSet 被改回、删掉 Pod 被重建；停掉 kube-controller-manager 后期望状态写进了 API 却没人执行，
# generation 与 observedGeneration 分离、conditions 不再更新；恢复后一次补齐
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：Docker；创建名为 csl-cl 的 kind 集群（Kubernetes 1.36.4），结束时删除；约 3 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=csl-cl
f="$OUT/output.tsv"; : >"$f"
out() { printf '%s\t%s\n' "$1" "$2" >>"$f"; }
k() { kctl "$C" "$@"; }
node() { docker exec "$C-control-plane" "$@"; }
pods() { k get pods -l app=web --no-headers 2>/dev/null | grep -vc Terminating || true; }
trap 'kind_down "$C" >/dev/null 2>&1 || true' EXIT

kind_up "$C"
k wait --for=condition=Ready node --all --timeout=180s >/dev/null
k apply -f - <manifests/web.yaml >/dev/null
k rollout status deploy/web --timeout=120s >/dev/null

# 一、手工把 ReplicaSet 改小：Deployment 控制器把它改回 3
RS=$(k get rs -l app=web -o jsonpath='{.items[0].metadata.name}')
k scale rs "$RS" --replicas=1 >/dev/null
back=no
for i in $(seq 10); do [ "$(k get rs "$RS" -o jsonpath='{.spec.replicas}')" = 3 ] && { back=yes; break; }; sleep 0.5; done
k wait --for=jsonpath='{.status.readyReplicas}'=3 "rs/$RS" --timeout=60s >/dev/null
out rs.revert "把 ReplicaSet 副本数改成 1：5 秒内被改回 3=$([ $back = yes ] && echo True || echo False)，之后就绪 Pod $(pods) 个"

# 二、删掉一个 Pod：ReplicaSet 控制器补一个新的
P=$(k get pods -l app=web -o jsonpath='{.items[0].metadata.name}')
k delete pod "$P" --wait=false >/dev/null
k wait --for=jsonpath='{.status.readyReplicas}'=3 "rs/$RS" --timeout=60s >/dev/null
out pod.recreate "删掉 Pod $( [ -n "$P" ] && echo 1 ) 个：随后又是 $(pods) 个，被删的名字不在其中=$(k get pods -l app=web -o name | grep -qx "pod/$P" && echo False || echo True)"
out gen.normal "$(k get deploy web -o jsonpath='正常时 generation={.metadata.generation}，observedGeneration={.status.observedGeneration}')"

# 三、停掉 kube-controller-manager（静态 Pod：移走清单，kubelet 就停掉它）
node mv /etc/kubernetes/manifests/kube-controller-manager.yaml /root/
for i in $(seq 60); do node crictl ps --name kube-controller-manager -q | grep -q . || break; sleep 1; done
k patch deploy web -p '{"spec":{"template":{"metadata":{"annotations":{"lab/version":"2"}}}}}' >/dev/null
P=$(k get pods -l app=web -o jsonpath='{.items[0].metadata.name}')
k delete pod "$P" --wait=false >/dev/null
sleep 10
out stopped.gen "$(k get deploy web -o jsonpath='控制器停止 10 秒：改了模板后 generation={.metadata.generation}，observedGeneration={.status.observedGeneration}')"
out stopped.pods "控制器停止期间删掉 1 个 Pod：剩 $(pods) 个，没有被补上；ReplicaSet 仍是 $(k get rs -l app=web --no-headers | wc -l | tr -d ' ') 个（新模板没有新建 ReplicaSet）"
out stopped.conditions "$(k get deploy web -o jsonpath='此时 conditions 仍是：{range .status.conditions[*]}{.type}={.status}（{.reason}） {end}' | sed 's/ *$//')"

# 四、恢复控制器：从 API 里的期望状态出发，一次补齐
node mv /root/kube-controller-manager.yaml /etc/kubernetes/manifests/
t0=$(python3 -c 'import time; print(time.time())')
k rollout status deploy/web --timeout=180s >/dev/null
el=$(python3 -c "import time; print(round(time.time() - $t0))")
out resumed "$(k get deploy web -o jsonpath='恢复后：observedGeneration={.status.observedGeneration}，')就绪 Pod $(pods) 个，ReplicaSet $(k get rs -l app=web --no-headers | wc -l | tr -d ' ') 个（旧的副本数 $(k get rs "$RS" -o jsonpath='{.spec.replicas}')）"
echo "controller_catch_up_seconds=$el" >"$OUT/timings.txt"

"$(kind_bin)" version >"$OUT/cluster-version.txt"
k version -o json | python3 -c 'import sys,json; print("server:", json.load(sys.stdin)["serverVersion"]["gitVersion"])' >>"$OUT/cluster-version.txt"
write_environment "$OUT/environment.txt" "kind_node_image: $KIND_NODE_IMAGE"
cat "$f" >&2
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
rs.revert	把 ReplicaSet 副本数改成 1：5 秒内被改回 3=True，之后就绪 Pod 3 个
pod.recreate	删掉 Pod 1 个：随后又是 3 个，被删的名字不在其中=True
gen.normal	正常时 generation=1，observedGeneration=1
stopped.gen	控制器停止 10 秒：改了模板后 generation=2，observedGeneration=1
stopped.pods	控制器停止期间删掉 1 个 Pod：剩 2 个，没有被补上；ReplicaSet 仍是 1 个（新模板没有新建 ReplicaSet）
stopped.conditions	此时 conditions 仍是：Progressing=True（NewReplicaSetAvailable） Available=True（MinimumReplicasAvailable）
resumed	恢复后：observedGeneration=2，就绪 Pod 3 个，ReplicaSet 2 个（旧的副本数 0）
EXPECTED
log "全部通过，输出在 $OUT"
