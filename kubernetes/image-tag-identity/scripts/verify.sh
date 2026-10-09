#!/usr/bin/env bash
# 镜像 tag 与摘要：同一个 tag 被重新推送之后，两个节点上按 tag 启动的 Pod 运行的是不是同一个镜像；拉取策略与按摘要部署
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：kind v0.33.0 创建的三节点集群（Kubernetes 1.37.0）与一个本地镜像仓库容器，结束时全部删除；约 3 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker curl
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
KIND_NODE_IMAGE="kindest/node:v1.37.0@sha256:a1ed56cfb0e7b93589bdf97c8cd566405a265939e3620fc4f5de89adff580ae5"
REG_IMAGE="registry@sha256:ddf754342cfc8acc51a56d5d0ab6af06826461864460636d8bd5c546dab2a7b8"
C=labs-iti; REG=labs-iti-registry; R=localhost:5055
cleanup() { docker rm -f "$REG" >/dev/null 2>&1 || true; kind_down "$C" >/dev/null 2>&1 || true; docker image rm -f "$R/app:1.0" "$R/app:latest" labs-iti-rebuild:1 labs-iti-rebuild:2 >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup
kind_up "$C" src/kind.yaml
docker run -d --name "$REG" -p 127.0.0.1:5055:5000 --network kind "$REG_IMAGE" >/dev/null
# 节点上的 containerd 把 localhost:5055 解析到仓库容器（明文 HTTP，只在这个实验的网络里）
for n in "$C-control-plane" "$C-worker" "$C-worker2"; do
  docker exec "$n" sh -c 'mkdir -p /etc/containerd/certs.d/localhost:5055 && printf "[host.\"http://labs-iti-registry:5000\"]\n" >/etc/containerd/certs.d/localhost:5055/hosts.toml'
done
kctl "$C" wait --for=condition=Ready node --all --timeout=180s >/dev/null

f="$OUT/output.tsv"; : >"$f"
# tag_digest <tag>：仓库里这个 tag 当前指向的摘要
tag_digest() { curl -fsSI -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json' "http://$R/v2/app/manifests/$1" | tr -d '\r' | awk -F': ' 'tolower($1)=="docker-content-digest" {print $2}'; }
# name_of <摘要>：把摘要换成 A、B 这样的代号，原始摘要另存
name_of() { case "$1" in "$DA") echo "摘要 A" ;; "${DB:-none}") echo "摘要 B" ;; *) echo "未知摘要 $1" ;; esac; }
# pod <名字> <节点> <镜像> [拉取策略]：创建 Pod，等它就绪，记录内容、策略、镜像摘要与 kubelet 的拉取事件
pod() {
  local name="$1" node="$2" image="$3" policy="${4:-}" spec
  spec="{\"spec\":{\"nodeName\":\"$C-$node\",\"terminationGracePeriodSeconds\":0}}"
  kctl "$C" run "$name" --image="$image" ${policy:+--image-pull-policy="$policy"} --overrides="$spec" >/dev/null
  kctl "$C" wait --for=condition=Ready "pod/$name" --timeout=120s >/dev/null
  local id ev
  id=$(kctl "$C" get pod "$name" -o jsonpath='{.status.containerStatuses[0].imageID}'); id="${id##*@}"
  ev=$(kctl "$C" get events --field-selector "involvedObject.name=$name,reason=Pulled" -o jsonpath='{.items[0].message}' | sed -E 's/ in [0-9.]+m?s.*//; s/ Image size.*//')
  printf '%s\t节点 %s\t镜像 %s\t策略 %s\t运行的内容 %s\t%s\t%s\n' "$5" "$node" "${image/$DA/<摘要 A>}" \
    "$(kctl "$C" get pod "$name" -o jsonpath='{.spec.containers[0].imagePullPolicy}')" "$(kctl "$C" logs "$name")" "$(name_of "$id")" "${ev/$DA/<摘要 A>}" >>"$f"
}
build_push() { docker build -q --build-arg CONTENT="$1" -t "$R/app:$2" src >/dev/null; docker push -q "$R/app:$2" >/dev/null; }

build_push build-A 1.0; DA=$(tag_digest 1.0)
pod p1 worker "$R/app:1.0" "" "step1.first_deploy"
build_push build-B 1.0; DB=$(tag_digest 1.0)
[ "$DA" != "$DB" ] || fail "两次构建应当得到不同的摘要"
printf 'step2.repush\t同一个 tag 1.0 重新推送了不同的内容：仓库里 tag 现在指向 %s\n' "$(name_of "$DB")" >>"$f"
pod p2 worker2 "$R/app:1.0" "" "step3.other_node"
id=$(kctl "$C" get pod p1 -o jsonpath='{.status.containerStatuses[0].imageID}')
printf 'step3.old_pod\t节点 worker 上一直在运行的 Pod：内容 %s\t%s\n' "$(kctl "$C" logs p1)" "$(name_of "${id##*@}")" >>"$f"
kctl "$C" delete pod p1 --wait=true >/dev/null
pod p1b worker "$R/app:1.0" "" "step4.recreate_same_node"
pod p3 worker "$R/app:1.0" Always "step5.always"
pod p4 worker "$R/app:1.0" "" "step6.if_not_present_after_always"
pod p5 worker "$R/app@$DA" "" "step7.by_digest"
pod p6 worker2 "$R/app@$DA" "" "step7.by_digest"
build_push build-B latest
pod p7 worker2 "$R/app:latest" "" "step8.latest_default_policy"
# 仓库不可用时：Always 需要联系仓库，IfNotPresent 用本地已有的镜像
docker stop "$REG" >/dev/null
kctl "$C" run p8 --image="$R/app:1.0" --image-pull-policy=Always --overrides="{\"spec\":{\"nodeName\":\"$C-worker\",\"terminationGracePeriodSeconds\":0}}" >/dev/null
reason=""
for _ in $(seq 1 60); do
  reason=$(kctl "$C" get pod p8 -o jsonpath='{.status.containerStatuses[0].state.waiting.reason}' 2>/dev/null || true)
  case "$reason" in ErrImagePull|ImagePullBackOff) break ;; esac
  /bin/sleep 1
done
printf 'step9.registry_down.always\t节点 worker\t策略 Always\t仓库停止后创建：容器状态 %s\n' "${reason/ImagePullBackOff/ErrImagePull}" >>"$f"
pod p9 worker "$R/app:1.0" "" "step9.registry_down.if_not_present"
docker start "$REG" >/dev/null
# 同一份 Dockerfile 与参数，不用缓存构建两次
docker build -q --no-cache --build-arg CONTENT=same -t labs-iti-rebuild:1 src >/dev/null; /bin/sleep 2
docker build -q --no-cache --build-arg CONTENT=same -t labs-iti-rebuild:2 src >/dev/null
r1=$(docker image inspect labs-iti-rebuild:1 --format '{{.Id}}'); r2=$(docker image inspect labs-iti-rebuild:2 --format '{{.Id}}')
printf 'step10.rebuild\t同一份 Dockerfile 与参数不用缓存构建两次，镜像 ID 相同 = %s\n' "$([ "$r1" = "$r2" ] && echo true || echo false)" >>"$f"

{ echo "digest_A=$DA"; echo "digest_B=$DB"; echo "rebuild_1=$r1"; echo "rebuild_2=$r2"; } >"$OUT/digests.txt"
write_environment "$OUT/environment.txt" "kind: $KIND_VERSION" "node_image: $KIND_NODE_IMAGE" "registry_image: $REG_IMAGE" \
  "kubernetes: $(kctl "$C" version -o json | python3 -c 'import sys,json; print(json.load(sys.stdin)["serverVersion"]["gitVersion"])')" \
  "containerd: $(docker exec "$C-worker" containerd --version | awk '{print $3}')"
cat "$f" >&2
# 摘要每次构建都不同，输出里用「摘要 A、摘要 B」代替，其余内容是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
step1.first_deploy	节点 worker	镜像 localhost:5055/app:1.0	策略 IfNotPresent	运行的内容 build-A	摘要 A	Successfully pulled image "localhost:5055/app:1.0"
step2.repush	同一个 tag 1.0 重新推送了不同的内容：仓库里 tag 现在指向 摘要 B
step3.other_node	节点 worker2	镜像 localhost:5055/app:1.0	策略 IfNotPresent	运行的内容 build-B	摘要 B	Successfully pulled image "localhost:5055/app:1.0"
step3.old_pod	节点 worker 上一直在运行的 Pod：内容 build-A	摘要 A
step4.recreate_same_node	节点 worker	镜像 localhost:5055/app:1.0	策略 IfNotPresent	运行的内容 build-A	摘要 A	Container image "localhost:5055/app:1.0" already present on machine and can be accessed by the pod
step5.always	节点 worker	镜像 localhost:5055/app:1.0	策略 Always	运行的内容 build-B	摘要 B	Successfully pulled image "localhost:5055/app:1.0"
step6.if_not_present_after_always	节点 worker	镜像 localhost:5055/app:1.0	策略 IfNotPresent	运行的内容 build-B	摘要 B	Container image "localhost:5055/app:1.0" already present on machine and can be accessed by the pod
step7.by_digest	节点 worker	镜像 localhost:5055/app@<摘要 A>	策略 IfNotPresent	运行的内容 build-A	摘要 A	Container image "localhost:5055/app@<摘要 A>" already present on machine and can be accessed by the pod
step7.by_digest	节点 worker2	镜像 localhost:5055/app@<摘要 A>	策略 IfNotPresent	运行的内容 build-A	摘要 A	Successfully pulled image "localhost:5055/app@<摘要 A>"
step8.latest_default_policy	节点 worker2	镜像 localhost:5055/app:latest	策略 Always	运行的内容 build-B	摘要 B	Successfully pulled image "localhost:5055/app:latest"
step9.registry_down.always	节点 worker	策略 Always	仓库停止后创建：容器状态 ErrImagePull
step9.registry_down.if_not_present	节点 worker	镜像 localhost:5055/app:1.0	策略 IfNotPresent	运行的内容 build-B	摘要 B	Container image "localhost:5055/app:1.0" already present on machine and can be accessed by the pod
step10.rebuild	同一份 Dockerfile 与参数不用缓存构建两次，镜像 ID 相同 = false
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
