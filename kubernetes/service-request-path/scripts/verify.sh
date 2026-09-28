#!/usr/bin/env bash
# 一次请求经过 Service、EndpointSlice、DNS 与 NetworkPolicy：kube-proxy 的规则、按连接而不是按请求分配、两种 Service 配错、search 与 ndots、策略挡住 DNS
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：Docker；创建名为 csl-sp 的 kind 集群（Kubernetes 1.36.4，1 个控制面 + 2 个工作节点，默认 CNI kindnet），结束时删除；约 6 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=csl-sp; W1=$C-worker
PY="python:3.14.7-slim@sha256:51dafde81dbdb6ebde285137a295cf18a47ca95234fe388a343719cb97305b3d"
f="$OUT/output.tsv"; : >"$f"
out() { printf '%s\t%s\n' "$1" "$2" >>"$f"; }
k() { kctl "$C" "$@"; }
cli() { local p=$1; shift; k exec "$p" -- python /code/client.py "$@"; }
trap 'kind_down "$C" >/dev/null 2>&1 || true' EXIT

kind_up "$C" config/kind.yaml
k wait --for=condition=Ready node --all --timeout=180s >/dev/null
docker exec "$C-control-plane" mkdir -p /opt/lab
docker cp src "$C-control-plane:/opt/lab/src" >/dev/null
k create configmap code --from-file=/opt/lab/src >/dev/null
# CoreDNS 打开 log 插件，用来数每次解析实际发出的查询
k get cm -n kube-system coredns -o json | python3 -c '
import json, sys
cm = json.load(sys.stdin)
cm["data"]["Corefile"] = cm["data"]["Corefile"].replace(".:53 {", ".:53 {\n    log", 1)
print(json.dumps(cm))' | k apply -f - >/dev/null
k rollout restart deploy/coredns -n kube-system >/dev/null
k rollout status deploy/coredns -n kube-system --timeout=120s >/dev/null

cat <<EOF | k apply -f - >/dev/null
apiVersion: apps/v1
kind: Deployment
metadata: {name: web}
spec:
  replicas: 3
  selector: {matchLabels: {app: web}}
  template:
    metadata: {labels: {app: web}}
    spec:
      volumes: [{name: code, configMap: {name: code}}]
      containers:
        - name: web
          image: "$PY"
          command: [python, /code/web.py]
          volumeMounts: [{name: code, mountPath: /code}]
          readinessProbe: {tcpSocket: {port: 8080}, periodSeconds: 1}
---
apiVersion: v1
kind: Service
metadata: {name: web}
spec: {selector: {app: web}, ports: [{port: 80, targetPort: 8080}]}
---
apiVersion: v1
kind: Service
metadata: {name: web-typo}
spec: {selector: {app: wbe}, ports: [{port: 80, targetPort: 8080}]}
---
apiVersion: v1
kind: Service
metadata: {name: web-badport}
spec: {selector: {app: web}, ports: [{port: 80, targetPort: 9090}]}
---
apiVersion: v1
kind: Service
metadata: {name: web-headless}
spec: {clusterIP: None, selector: {app: web}, ports: [{port: 80, targetPort: 8080}]}
EOF
for p in cli fe; do
  cat <<EOF | k apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata: {name: $p, labels: {role: $([ $p = fe ] && echo frontend || echo other)}}
spec:
  volumes: [{name: code, configMap: {name: code}}]
  containers: [{name: c, image: "$PY", command: [sleep, infinity], volumeMounts: [{name: code, mountPath: /code}]}]
EOF
done
k rollout status deploy/web --timeout=180s >/dev/null
k wait --for=condition=Ready pod/cli pod/fe --timeout=120s >/dev/null

# 1. Service → EndpointSlice → kube-proxy 规则
CIP=$(k get svc web -o jsonpath='{.spec.clusterIP}')
out svc.basic "ClusterIP $CIP；kube-proxy 模式：$(k get cm -n kube-system kube-proxy -o jsonpath='{.data.config\.conf}' | sed -n 's/^mode: //p')"
out svc.endpointslice "EndpointSlice 中的端点（地址 就绪 节点）：$(k get endpointslice -l kubernetes.io/service-name=web -o jsonpath='{range .items[*].endpoints[*]}{.addresses[0]} {.conditions.ready} {.nodeName}；{end}')"
docker exec "$W1" iptables-save -t nat >"$OUT/iptables-nat-worker.txt"
out svc.iptables "$W1 上 default/web 的规则：$(python3 - "$OUT/iptables-nat-worker.txt" <<'PY'
import re, sys
rules = [l for l in open(sys.argv[1]) if "-A KUBE-SVC-" in l and "default/web -> " in l]
parts = []
for l in rules:
    p = re.search(r"--probability ([\d.]+)", l)
    parts.append(f"{float(p[1]):.2f}" if p else "其余全部")
print(f"{len(rules)} 条跳转到端点的规则，依次按概率 " + " / ".join(parts) + " 选择")
PY
)"
# 2. 按连接分配：每次新建连接 vs 复用一条长连接
out svc.spread "300 次请求，$(cli cli spread web 300)"
# 3. 两种配错：选择器写错（没有端点）与端口写错（有端点）
out svc.typo "选择器写错：端点数 $(k get endpointslice -l kubernetes.io/service-name=web-typo -o jsonpath='{range .items[*].endpoints[*]}x{end}' | wc -c | tr -d ' ')；请求：$(cli cli probe web-typo)"
out svc.typo_rule "$W1 filter 表中 web-typo 的规则：$(docker exec "$W1" iptables-save -t filter | grep 'default/web-typo' | sed -E 's/ -d [0-9.]+\/32//; s/^-A //' | tr '\n' ' ')"
out svc.badport "targetPort 写错：端点数 $(k get endpointslice -l kubernetes.io/service-name=web-badport -o jsonpath='{range .items[*].endpoints[*]}x{end}' | wc -c | tr -d ' ')；请求：$(cli cli probe web-badport)"

# 4. DNS：search 列表、ndots，以及一次解析实际发出多少查询
out dns.resolv "$(k exec cli -- cat /etc/resolv.conf | grep -E '^(search|options)' | paste -sd '|' - | sed 's/|/；/g')"
out dns.service "web → $(cli cli resolve web)；web-headless → $(cli cli resolve web-headless)"
count_queries() {  # count_queries <名字> <文件名>：解析一次，按 CoreDNS Pod 各自的日志偏移统计新收到的查询
  local pods=($(k get pods -n kube-system -l k8s-app=kube-dns -o name)) before=() i r
  for i in "${!pods[@]}"; do before[$i]=$(k logs -n kube-system "${pods[$i]}" | wc -l | tr -d ' '); done
  r=$(cli cli resolve "$1")
  sleep 2
  : >"$OUT/dns-$2.log"
  for i in "${!pods[@]}"; do k logs -n kube-system "${pods[$i]}" | tail -n +"$((before[$i] + 1))" >>"$OUT/dns-$2.log"; done
  echo "$r；$(python3 - "$OUT/dns-$2.log" <<'PY'
import re, sys
q = sorted(set(re.findall(r'"(A|AAAA) IN (\S+) [^"]*" (\w+)', open(sys.argv[1]).read())), key=lambda x: (x[1], x[0]))
print(f"CoreDNS 收到 {len(q)} 个不同的查询：" + "；".join(f"{t} {n} {rc}" for t, n, rc in q))
PY
)"
}
out dns.short "解析 web：$(count_queries web short)"
out dns.external "解析 example.com：$(count_queries example.com external)"
out dns.fqdn "解析 example.com.（末尾带点）：$(count_queries example.com. fqdn)"

# 5. NetworkPolicy
cat <<'EOF' | k apply -f - >/dev/null
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: {name: default-deny-ingress}
spec: {podSelector: {}, policyTypes: [Ingress]}
EOF
sleep 5
out np.deny "默认拒绝入站后：cli → web $(cli cli probe web)"
cat <<'EOF' | k apply -f - >/dev/null
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: {name: web-allow-frontend}
spec:
  podSelector: {matchLabels: {app: web}}
  policyTypes: [Ingress]
  ingress: [{from: [{podSelector: {matchLabels: {role: frontend}}}], ports: [{port: 8080}]}]
EOF
sleep 5
out np.allow "放行 role=frontend 后：cli → web $(cli cli probe web)；fe → web $(cli fe probe web)"
cat <<'EOF' | k apply -f - >/dev/null
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: {name: frontend-egress}
spec:
  podSelector: {matchLabels: {role: frontend}}
  policyTypes: [Egress]
  egress: [{to: [{podSelector: {matchLabels: {app: web}}}], ports: [{port: 8080}]}]
EOF
sleep 5
out np.egress "fe 只允许出站到 web:8080：解析 web $(cli fe resolve web)；按 ClusterIP 请求 $(cli fe probe "$CIP")"
cat <<'EOF' | k apply -f - >/dev/null
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: {name: frontend-egress-dns}
spec:
  podSelector: {matchLabels: {role: frontend}}
  policyTypes: [Egress]
  egress:
    - to: [{namespaceSelector: {matchLabels: {kubernetes.io/metadata.name: kube-system}}, podSelector: {matchLabels: {k8s-app: kube-dns}}}]
      ports: [{port: 53, protocol: UDP}, {port: 53, protocol: TCP}]
EOF
sleep 5
out np.egress_dns "再放行到 CoreDNS 的 53 端口：解析 web $(cli fe resolve web)；按名字请求 $(cli fe probe web)"

k version -o json | python3 -c 'import json,sys; v=json.load(sys.stdin); print("kubernetes_server:", v["serverVersion"]["gitVersion"])' >"$OUT/cluster-version.txt"
"$(kind_bin)" version >>"$OUT/cluster-version.txt"
k get ds -n kube-system kindnet -o jsonpath='{.spec.template.spec.containers[0].image}' | sed 's/^/kindnet_image: /' >>"$OUT/cluster-version.txt"; echo >>"$OUT/cluster-version.txt"
cp config/kind.yaml "$OUT/kind-config.yaml"
write_environment "$OUT/environment.txt" "kind_node_image: $KIND_NODE_IMAGE" "app_image: $PY"
cat "$f" >&2
expect_regex "$f" "^svc\.iptables	.*3 条跳转到端点的规则，依次按概率 0\.33 / 0\.50 / 其余全部 选择$" "kube-proxy 按概率在 3 个端点间选择"
expect_regex "$f" "^svc\.spread	.*新建连接：[0-9]+ [0-9]+ [0-9]+（3 个 Pod）；复用一条连接：300（1 个 Pod）$" "按连接分配：长连接上的请求全部落到一个 Pod"
expect_regex "$f" "^svc\.typo	选择器写错：端点数 0；请求：连接被拒绝" "选择器写错：没有端点，连接被拒绝"
expect_regex "$f" "^svc\.typo_rule	.*has no endpoints.*-j REJECT" "没有端点的 Service：kube-proxy 写入 REJECT 规则"
expect_regex "$f" "^svc\.badport	targetPort 写错：端点数 3；请求：连接被拒绝" "端口写错：有端点，仍然连接被拒绝"
expect_regex "$f" "^dns\.resolv	search default\.svc\.cluster\.local svc\.cluster\.local cluster\.local.*；options ndots:5" "Pod 的 search 列表与 ndots:5"
expect_regex "$f" "^dns\.service	web → 10\.96\.[0-9.]+，.*；web-headless → 10\.244\.[0-9.]+ 10\.244\.[0-9.]+ 10\.244\.[0-9.]+，" "普通 Service 解析到 ClusterIP，无头 Service 解析到 Pod IP"
expect_regex "$f" "^np\.deny	默认拒绝入站后：cli → web 超时" "默认拒绝入站：请求被丢弃，表现为超时"
expect_regex "$f" "^np\.allow	放行 role=frontend 后：cli → web 超时，.*；fe → web HTTP 200" "按标签放行：只有 frontend 能访问"
expect_regex "$f" "^np\.egress	.*解析 web 解析失败.*；按 ClusterIP 请求 HTTP 200" "只放行到 web 的出站：DNS 被挡住，按 IP 能通"
expect_regex "$f" "^np\.egress_dns	.*按名字请求 HTTP 200" "放行 DNS 后按名字能通"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
g = lambda k: re.search(rf"^{re.escape(k)}\t(.*)$", t, re.M)[1]
n = lambda k: int(re.search(r"收到 (\d+) 个不同的查询", g(k))[1])
assert n("dns.short") == 2, g("dns.short")                         # web 在第一个 search 域就命中：A + AAAA
ext = g("dns.external")
for d in ["example.com.default.svc.cluster.local.", "example.com.svc.cluster.local.", "example.com.cluster.local."]:
    assert f"A {d} NXDOMAIN" in ext, (d, ext)                       # 先把三个 search 域都试一遍
assert n("dns.external") == 8 and n("dns.fqdn") == 2, (n("dns.external"), n("dns.fqdn"))
ms = int(re.search(r"解析失败.*?耗时 (\d+)ms", g("np.egress"))[1])
assert ms >= 5000, ms                                               # DNS 被丢弃时要等超时重试
print(f"通过：example.com 触发 {n('dns.external')} 个查询，末尾带点只有 {n('dns.fqdn')} 个；DNS 被策略挡住时解析 {ms}ms 后失败")
PY
log "全部通过，输出在 $OUT"
