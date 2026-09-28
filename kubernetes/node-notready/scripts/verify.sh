#!/usr/bin/env bash
# 节点 NotReady：kubelet 与静态 Pod 的启动链、没有 CNI 时节点与 Pod 的状态、Ready 之后跨节点仍不通、kubelet 停止后的驱逐与仍在运行的旧容器
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：Docker；创建名为 csl-nn 的 kind 集群（Kubernetes 1.36.4，1 个控制面 + 2 个工作节点，关闭默认 CNI），结束时删除；约 5 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=csl-nn; CP=$C-control-plane; W1=$C-worker; W2=$C-worker2
BB="busybox:1.37@sha256:bdf57e528e45e4433820e045b29b4597825a1c9e38353532d90a01445013f82e"
f="$OUT/output.tsv"; : >"$f"
out() { printf '%s\t%s\n' "$1" "$2" >>"$f"; }
now() { python3 -c 'import time; print(f"{time.time():.1f}")'; }
since() { python3 -c "print(f'{$(now) - $1:.1f}')"; }
k() { kctl "$C" "$@"; }
ready_of() { k get node "$1" -o jsonpath='{range .status.conditions[?(@.type=="Ready")]}{.status} {.reason}: {.message}{end}'; }
events_of() { k get events --field-selector "involvedObject.name=$1" -o jsonpath='{range .items[*]}{.reason}: {.message}{"\n"}{end}'; }
trap 'docker exec "$W1" systemctl start kubelet >/dev/null 2>&1 || true; kind_down "$C" >/dev/null 2>&1 || true' EXIT

kind_up "$C" config/kind.yaml
until [ "$(k get nodes --no-headers 2>/dev/null | wc -l | tr -d ' ')" = 3 ]; do sleep 1; done
sleep 10

# 1. 启动链：systemd 拉起 containerd 与 kubelet，kubelet 从静态 Pod 目录拉起控制面组件
out chain.services "控制面节点上：containerd $(docker exec "$CP" systemctl is-active containerd)，kubelet $(docker exec "$CP" systemctl is-active kubelet)"
out chain.static "kubelet $(docker exec "$CP" grep staticPodPath /var/lib/kubelet/config.yaml)；目录内容：$(docker exec "$CP" ls /etc/kubernetes/manifests | tr '\n' ' ')"
out chain.mirror "kube-apiserver Pod 的 config.source 注解：$(k get pod -n kube-system "kube-apiserver-$CP" -o jsonpath='{.metadata.annotations.kubernetes\.io/config\.source}')，hostNetwork=$(k get pod -n kube-system "kube-apiserver-$CP" -o jsonpath='{.spec.hostNetwork}')"
out chain.cni_dir "工作节点 /etc/cni/net.d 下的文件数：$(docker exec "$W1" sh -c 'ls /etc/cni/net.d | wc -l' | tr -d ' ')；/opt/cni/bin：$(docker exec "$W1" ls /opt/cni/bin | tr '\n' ' ')"

# 2. 没有 CNI：节点 NotReady，调度器挡住普通 Pod，容忍污点的 Pod 卡在创建沙箱
out nocni.node "$(ready_of "$W1")"
out nocni.taints "工作节点污点：$(k get node "$W1" -o jsonpath='{range .spec.taints[*]}{.key}:{.effect} {end}')"
out nocni.system "kube-system：$(k get pods -n kube-system --no-headers | awk '{print $1"="$3}' | tr '\n' ' ')"
cat <<EOF | k apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata: {name: plain}
spec: {containers: [{name: c, image: "$BB", command: [sleep, "3600"]}]}
---
apiVersion: v1
kind: Pod
metadata: {name: tolerant}
spec:
  nodeName: $W1
  tolerations: [{key: node.kubernetes.io/not-ready, operator: Exists, effect: NoSchedule}]
  containers: [{name: c, image: "$BB", command: [sleep, "3600"]}]
EOF
sleep 20
out nocni.plain "$(k get pod plain -o jsonpath='{.status.phase}')；$(events_of plain | grep -m1 FailedScheduling)"
out nocni.tolerant "$(k get pod tolerant -o jsonpath='{.status.phase} {.status.containerStatuses[0].state.waiting.reason}')；$(events_of tolerant | grep -m1 NetworkNotReady)"
out nocni.default_tolerations "普通 Pod 自动获得的容忍：$(k get pod plain -o jsonpath='{range .spec.tolerations[*]}{.key}={.tolerationSeconds}s {end}')"

# 3. 只给控制面节点写 CNI 配置：它就绪，工作节点仍然 NotReady——就绪是按节点判断的
t=$(now); scripts/cni-conf.sh "$CP" "$(k get node "$CP" -o jsonpath='{.spec.podCIDR}')"
k wait --for=condition=Ready "node/$CP" --timeout=60s >/dev/null
out cni.cp "写入配置后 $(since "$t") 秒控制面节点就绪；工作节点：$(ready_of "$W1" | cut -d: -f1)"
k wait --for=condition=Ready pod -n kube-system -l k8s-app=kube-dns --timeout=90s >/dev/null
out cni.coredns "CoreDNS：$(k get pods -n kube-system -l k8s-app=kube-dns -o jsonpath='{range .items[*]}{.status.phase}@{.spec.nodeName} {end}')"

# 4. 工作节点也写配置：全部就绪，Pod 能启动，但跨节点不通（没有路由），DNS 解析失败
for n in "$W1" "$W2"; do scripts/cni-conf.sh "$n" "$(k get node "$n" -o jsonpath='{.spec.podCIDR}')"; done
k wait --for=condition=Ready node --all --timeout=60s >/dev/null
k wait --for=condition=Ready pod/plain pod/tolerant --timeout=90s >/dev/null
out cni.all "节点：$(k get nodes --no-headers | awk '{print $1"="$2}' | tr '\n' ' ')；plain 在 $(k get pod plain -o jsonpath='{.spec.nodeName}') 上 $(k get pod plain -o jsonpath='{.status.phase}')"
DNSIP=$(k get pods -n kube-system -l k8s-app=kube-dns -o jsonpath='{.items[0].status.podIP}')
probe_net() {  # 从 plain 访问控制面节点上的 CoreDNS：Pod IP 直连与 DNS 解析
  local a b
  a=$(k exec plain -- wget -T 2 -qO- "http://$DNSIP:8080/health" 2>&1 | tr '\n' ' ' || true)
  b=$(k exec plain -- nslookup -timeout=2 kubernetes.default.svc.cluster.local 2>&1 | grep -m1 -E "^Address: 10\.96|timed out|no servers" || true)
  echo "直连 CoreDNS Pod：${a:-无输出}；解析 kubernetes.default：${b:-无结果}"
}
out net.before "$(probe_net)"
routes=$(docker exec "$W1" ip route | grep -E '^10\.244\.[0-9]+\.0/24' | tr '\n' ' ' || true)
out net.routes_before "工作节点路由表中的 Pod 网段：${routes:-无}"
for a in "$CP" "$W1" "$W2"; do
  for b in "$CP" "$W1" "$W2"; do
    [ "$a" = "$b" ] && continue
    docker exec "$a" ip route add "$(k get node "$b" -o jsonpath='{.spec.podCIDR}')" via "$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$b")"
  done
done
out net.after "$(probe_net)"

# 5. 停掉 kubelet：节点多久变成 NotReady、Pod 多久被驱逐重建，旧容器是否还在运行
k delete pod plain tolerant --wait=false >/dev/null
for n in "$W1" "$W2"; do docker exec "$n" crictl pull "$BB" >/dev/null; done   # 预先拉取镜像，让重建时间只反映调度与启动
k cordon "$W2" >/dev/null
cat <<EOF | k apply -f - >/dev/null
apiVersion: apps/v1
kind: Deployment
metadata: {name: web}
spec:
  replicas: 1
  selector: {matchLabels: {app: web}}
  template:
    metadata: {labels: {app: web}}
    spec:
      terminationGracePeriodSeconds: 5
      tolerations:
        - {key: node.kubernetes.io/unreachable, operator: Exists, effect: NoExecute, tolerationSeconds: 20}
        - {key: node.kubernetes.io/not-ready, operator: Exists, effect: NoExecute, tolerationSeconds: 20}
      containers:
        - name: web
          image: "$BB"
          command: [sh, -c, 'mkdir /www && hostname > /www/index.html && exec httpd -f -p 8080 -h /www']
EOF
k rollout status deploy/web --timeout=90s >/dev/null
k uncordon "$W2" >/dev/null
OLD=$(k get pods -l app=web -o jsonpath='{.items[0].metadata.name}'); OLDIP=$(k get pod "$OLD" -o jsonpath='{.status.podIP}')
out stop.before "web Pod $OLD 运行在 $(k get pod "$OLD" -o jsonpath='{.spec.nodeName}')"
t=$(now); docker exec "$W1" systemctl stop kubelet
until [ "$(k get node "$W1" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')" != True ]; do sleep 1; done
out stop.notready "停止 kubelet 后 $(since "$t") 秒节点状态变化：$(ready_of "$W1")"
sleep 2
out stop.taints "污点：$(k get node "$W1" -o jsonpath='{range .spec.taints[*]}{.key}:{.effect} {end}')"
until [ -n "$(k get pod "$OLD" -o jsonpath='{.metadata.deletionTimestamp}')" ]; do sleep 1; done
out stop.evicted "停止 kubelet 后 $(since "$t") 秒旧 Pod 被标记删除（容忍 20 秒）"
until k get pods -l app=web --field-selector status.phase=Running -o jsonpath='{range .items[*]}{.spec.nodeName}{"\n"}{end}' | grep -qx "$W2"; do sleep 1; done
NEW=$(k get pods -l app=web --field-selector spec.nodeName="$W2" -o jsonpath='{.items[0].metadata.name}'); NEWIP=$(k get pod "$NEW" -o jsonpath='{.status.podIP}')
out stop.replaced "停止 kubelet 后 $(since "$t") 秒新 Pod 在 $W2 上运行"
sleep 20
out stop.api "API 中的 web Pod：$(k get pods -l app=web --no-headers | awk '{print $3}' | sort | tr '\n' ' ')"
out stop.old_container "$W1 上的容器：$(docker exec "$W1" crictl ps --name web -o json | python3 -c 'import json,sys; print(" ".join(c["state"] for c in json.load(sys.stdin)["containers"]) or "无")')"
out stop.old_serving "访问旧 Pod IP：$(docker exec "$CP" curl -s -m 2 "http://$OLDIP:8080/" || echo 失败)；访问新 Pod IP：$(docker exec "$CP" curl -s -m 2 "http://$NEWIP:8080/" || echo 失败)"
t=$(now); docker exec "$W1" systemctl start kubelet
until ! k get pod "$OLD" >/dev/null 2>&1; do sleep 1; done
out restart.gone "恢复 kubelet 后 $(since "$t") 秒旧 Pod 从 API 删除；$W1 上仍在运行的 web 容器：$(docker exec "$W1" crictl ps --name web -q | wc -l | tr -d ' ') 个；节点：$(ready_of "$W1" | cut -d: -f1)"

k version -o json | python3 -c 'import json,sys; v=json.load(sys.stdin); print("kubernetes_server:", v["serverVersion"]["gitVersion"])' >"$OUT/cluster-version.txt"
"$(kind_bin)" version >>"$OUT/cluster-version.txt"
cp config/kind.yaml "$OUT/kind-config.yaml"
docker exec "$W1" cat /etc/cni/net.d/10-lab.conflist >"$OUT/cni-conflist.json"
write_environment "$OUT/environment.txt" "kind_node_image: $KIND_NODE_IMAGE" "busybox: $BB"
cat "$f" >&2
expect_line "$f" "chain.mirror	kube-apiserver Pod 的 config.source 注解：file，hostNetwork=true" "控制面组件是 kubelet 从文件拉起的静态 Pod，使用宿主机网络"
expect_regex "$f" "^chain\.cni_dir	工作节点 /etc/cni/net\.d 下的文件数：0；" "关闭默认 CNI 后没有 CNI 配置"
expect_regex "$f" "^nocni\.node	False KubeletNotReady: .*cni plugin not initialized" "没有 CNI：节点 NotReady，原因是网络插件未初始化"
expect_line "$f" "nocni.taints	工作节点污点：node.kubernetes.io/not-ready:NoSchedule " "NotReady 节点带 not-ready:NoSchedule 污点"
expect_regex "$f" "^nocni\.system	.*coredns-[a-z0-9-]+=Pending .*kube-apiserver-[a-z-]+=Running .*kube-proxy-[a-z0-9]+=Running" "使用宿主机网络的组件照常运行，CoreDNS 等待"
expect_regex "$f" "^nocni\.plain	Pending；FailedScheduling: .*untolerated taint" "普通 Pod 被调度器挡住"
expect_regex "$f" "^nocni\.tolerant	Pending ContainerCreating；NetworkNotReady: " "容忍污点的 Pod 卡在创建网络沙箱"
expect_line "$f" "nocni.default_tolerations	普通 Pod 自动获得的容忍：node.kubernetes.io/not-ready=300s node.kubernetes.io/unreachable=300s " "默认容忍 300 秒"
expect_regex "$f" "^cni\.cp	.*；工作节点：False KubeletNotReady$" "只配置控制面节点时，工作节点仍然 NotReady"
expect_regex "$f" "^cni\.all	节点：[^ ]+=Ready [^ ]+=Ready [^ ]+=Ready ；plain 在 .* 上 Running$" "所有节点就绪，Pod 运行"
expect_regex "$f" "^net\.before	直连 CoreDNS Pod：wget: download timed out.*；解析 kubernetes\.default：.*(timed out|no servers)" "节点就绪但跨节点不通，DNS 解析失败"
expect_line "$f" "net.routes_before	工作节点路由表中的 Pod 网段：无" "工作节点没有其他节点 Pod 网段的路由"
expect_line "$f" "net.after	直连 CoreDNS Pod：OK；解析 kubernetes.default：Address: 10.96.0.1" "补上路由后跨节点与 DNS 恢复"
expect_regex "$f" "^stop\.notready	.*Unknown NodeStatusUnknown: Kubelet stopped posting node status\.$" "kubelet 停止后节点变为 Unknown"
expect_regex "$f" "^stop\.taints	.*node\.kubernetes\.io/unreachable:NoExecute" "加上 unreachable:NoExecute 污点"
expect_line "$f" "stop.api	API 中的 web Pod：Running Terminating " "新 Pod 运行，旧 Pod 停在 Terminating"
expect_line "$f" "stop.old_container	csl-nn-worker 上的容器：CONTAINER_RUNNING" "旧容器仍在节点上运行"
expect_regex "$f" "^stop\.old_serving	访问旧 Pod IP：web-[a-z0-9-]+；访问新 Pod IP：web-[a-z0-9-]+$" "新旧两个实例同时在处理请求"
expect_regex "$f" "^restart\.gone	.*仍在运行的 web 容器：0 个；节点：True KubeletReady$" "kubelet 恢复后旧 Pod 与容器被清理"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
sec = lambda k: float(re.search(rf"^{k}\t.*?([\d.]+) 秒", t, re.M)[1])
nr, ev = sec("stop\\.notready"), sec("stop\\.evicted")
assert 30 <= nr <= 70, nr                  # node-monitor-grace-period 默认 50 秒，加上检查周期与心跳间隔
assert ev - nr >= 15, (nr, ev)             # 驱逐发生在节点 Unknown 之后，至少等过容忍时间
print(f"通过：kubelet 停止 {nr} 秒后节点 Unknown，{ev} 秒后旧 Pod 被驱逐")
PY
log "全部通过，输出在 $OUT"
