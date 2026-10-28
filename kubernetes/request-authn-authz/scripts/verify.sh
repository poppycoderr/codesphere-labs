#!/usr/bin/env bash
# 集群里的一次请求经过认证、授权、准入：匿名请求、curl -k 的含义、kubectl proxy 用的是谁的身份、ServiceAccount 令牌的受众与有效期、
# Secret 的 base64 与 etcd 里的明文、kubectl auth can-i 与最小权限、准入拒绝管理员的特权 Pod。只读操作与合成的 ServiceAccount
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：Docker；创建名为 csl-ra 的 kind 集群（Kubernetes 1.36.4），结束时删除；约 2 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=csl-ra
f="$OUT/output.tsv"; : >"$f"
out() { printf '%s\t%s\n' "$1" "$2" >>"$f"; }
k() { kctl "$C" "$@"; }
node() { docker exec "$C-control-plane" "$@"; }
API=https://127.0.0.1:6443
# code <curl 参数...>：只取 HTTP 状态码
code() { node curl -s -o /dev/null -w '%{http_code}' "$@" || true; }
trap 'rc=$?; kind_down "$C" >/dev/null 2>&1 || true; exit $rc' EXIT

kind_up "$C"
k wait --for=condition=Ready node --all --timeout=180s >/dev/null
k apply -f - <manifests/lab.yaml >/dev/null
k wait --for=condition=Ready pod/app -n lab --timeout=120s >/dev/null

# 一、认证：没有凭据就是匿名用户
out anon.version "不带任何凭据（curl -k）：GET /version 返回 $(code -k $API/version)，GET /api/v1/namespaces 返回 $(code -k $API/api/v1/namespaces)"
out anon.user "$(node curl -sk $API/api/v1/namespaces | python3 -c 'import sys,json; m=json.load(sys.stdin)["message"]; print("拒绝信息里的用户：" + m.split("\"")[1])')"
rc=$(node sh -c "curl -s -o /dev/null $API/version; echo \$?")
out tls "去掉 -k：curl 退出码 ${rc}（服务端证书由集群自己的 CA 签发，客户端不信任）；加上 --cacert 指定集群 CA：$(code --cacert /etc/kubernetes/pki/ca.crt $API/version)"

# 二、ServiceAccount 令牌：受众与有效期
TOKEN=$(k create token reader -n lab --duration 10m)
OTHER=$(k create token reader -n lab --duration 10m --audience https://other.example)
out token.ok "reader 的令牌：列出 lab 的 Pod $(code -k -H "Authorization: Bearer $TOKEN" $API/api/v1/namespaces/lab/pods)，列出 lab 的 Secret $(code -k -H "Authorization: Bearer $TOKEN" $API/api/v1/namespaces/lab/secrets)，列出 default 的 Pod $(code -k -H "Authorization: Bearer $TOKEN" $API/api/v1/namespaces/default/pods)"
out token.audience "受众是别的服务的令牌：$(code -k -H "Authorization: Bearer $OTHER" $API/api/v1/namespaces/lab/pods)（认证失败）"
claims() { python3 -c 'import sys,json,base64; p=sys.argv[1].split(".")[1]; p+="="*(-len(p)%4); c=json.loads(base64.urlsafe_b64decode(p)); r={"aud":c["aud"],"ttl":c["exp"]-c["iat"]}; w=c.get("kubernetes.io",{}).get("warnafter"); r.update({"warnafter":w-c["iat"]} if w else {}); print(json.dumps(r))' "$1"; }
out token.created "kubectl create token --duration 10m：$(claims "$TOKEN")"
UID_=$(k get pod app -n lab -o jsonpath='{.metadata.uid}')
PT=$(node sh -c "cat /var/lib/kubelet/pods/$UID_/volumes/kubernetes.io~projected/kube-api-access-*/token")
out token.projected "Pod 里自动挂载的投射令牌：$(claims "$PT")（秒）"

# 三、kubectl proxy 用的是启动它的那份凭据
node sh -c 'kubectl --kubeconfig /etc/kubernetes/admin.conf proxy --port 8001 >/dev/null 2>&1 &'
node sh -c "kubectl config --kubeconfig /tmp/reader.conf set-cluster c --server=$API --certificate-authority=/etc/kubernetes/pki/ca.crt >/dev/null
  kubectl config --kubeconfig /tmp/reader.conf set-credentials r --token=$TOKEN >/dev/null
  kubectl config --kubeconfig /tmp/reader.conf set-context x --cluster=c --user=r >/dev/null
  kubectl config --kubeconfig /tmp/reader.conf use-context x >/dev/null
  kubectl --kubeconfig /tmp/reader.conf proxy --port 8002 >/dev/null 2>&1 &"
sleep 3
out proxy "经 kubectl proxy 列出 lab 的 Secret：管理员凭据启动的 proxy 返回 $(code http://127.0.0.1:8001/api/v1/namespaces/lab/secrets)，reader 凭据启动的 proxy 返回 $(code http://127.0.0.1:8002/api/v1/namespaces/lab/secrets)"

# 四、授权：can-i 检查最小权限
SA=system:serviceaccount:lab:reader
can() { k auth can-i "$@" --as "$SA" -n lab 2>/dev/null || true; }
out can-i "kubectl auth can-i --as reader：list pods=$(can list pods)，delete pods=$(can delete pods)，get secrets=$(can get secrets)，list pods -n default=$(k auth can-i list pods --as "$SA" -n default 2>/dev/null || true)"

# 五、Secret：base64 不是加密；etcd 里默认也是明文
B64=$(k get secret demo -n lab -o jsonpath='{.data.password}')
out secret.base64 "Secret 的 data.password 是 ${B64}，base64 解码得到 $(echo "$B64" | base64 -d)"
ETCD=$(node crictl ps --name etcd -q | head -1)
n=$(node crictl exec "$ETCD" etcdctl --endpoints https://127.0.0.1:2379 --cacert /etc/kubernetes/pki/etcd/ca.crt \
  --cert /etc/kubernetes/pki/etcd/server.crt --key /etc/kubernetes/pki/etcd/server.key get /registry/secrets/lab/demo | grep -ac example_password || true)
out secret.etcd "直接读 etcd 里这个 Secret 的存储值，包含明文 example_password=$([ "$n" -gt 0 ] && echo True || echo False)（没有配置静态加密）"

# 六、准入：认证、授权都通过，仍可能被拒绝
k label ns lab pod-security.kubernetes.io/enforce=restricted --overwrite >/dev/null 2>&1
msg=$(cat <<'Y' | k apply -f - 2>&1 || true
apiVersion: v1
kind: Pod
metadata: {name: priv, namespace: lab}
spec:
  containers:
    - name: pause
      image: registry.k8s.io/pause:3.10
      imagePullPolicy: Never
      securityContext: {privileged: true}
Y
)
out admission "命名空间设为 restricted 后，管理员创建特权 Pod：被拒绝=$(echo "$msg" | grep -q 'violates PodSecurity' && echo True || echo False)（PodSecurity 准入）；管理员对 pods 的 create 权限=$(k auth can-i create pods -n lab)"

"$(kind_bin)" version >"$OUT/cluster-version.txt"
k version -o json | python3 -c 'import sys,json; print("server:", json.load(sys.stdin)["serverVersion"]["gitVersion"])' >>"$OUT/cluster-version.txt"
write_environment "$OUT/environment.txt" "kind_node_image: $KIND_NODE_IMAGE"
cat "$f" >&2
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
anon.version	不带任何凭据（curl -k）：GET /version 返回 200，GET /api/v1/namespaces 返回 403
anon.user	拒绝信息里的用户：system:anonymous
tls	去掉 -k：curl 退出码 60（服务端证书由集群自己的 CA 签发，客户端不信任）；加上 --cacert 指定集群 CA：200
token.ok	reader 的令牌：列出 lab 的 Pod 200，列出 lab 的 Secret 403，列出 default 的 Pod 403
token.audience	受众是别的服务的令牌：401（认证失败）
token.created	kubectl create token --duration 10m：{"aud": ["https://kubernetes.default.svc.cluster.local"], "ttl": 600}
token.projected	Pod 里自动挂载的投射令牌：{"aud": ["https://kubernetes.default.svc.cluster.local"], "ttl": 31536000, "warnafter": 3607}（秒）
proxy	经 kubectl proxy 列出 lab 的 Secret：管理员凭据启动的 proxy 返回 200，reader 凭据启动的 proxy 返回 403
can-i	kubectl auth can-i --as reader：list pods=yes，delete pods=no，get secrets=no，list pods -n default=no
secret.base64	Secret 的 data.password 是 ZXhhbXBsZV9wYXNzd29yZA==，base64 解码得到 example_password
secret.etcd	直接读 etcd 里这个 Secret 的存储值，包含明文 example_password=True（没有配置静态加密）
admission	命名空间设为 restricted 后，管理员创建特权 Pod：被拒绝=True（PodSecurity 准入）；管理员对 pods 的 create 权限=yes
EXPECTED
log "全部通过，输出在 $OUT"
