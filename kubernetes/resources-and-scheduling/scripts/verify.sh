#!/usr/bin/env bash
# Pending、OOMKilled、Evicted：调度看 requests 而不是实际用量、优先级抢占、QoS 与 oom_score_adj、CPU limit 的限流延迟、内存超限与临时存储超限
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：Docker；创建名为 csl-rs 的 kind 集群（Kubernetes 1.36.4，1 个控制面 + 1 个工作节点），结束时删除；约 6 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=csl-rs; W=$C-worker
f="$OUT/output.tsv"; : >"$f"
out() { printf '%s\t%s\n' "$1" "$2" >>"$f"; }
now() { python3 -c 'import time; print(f"{time.time():.1f}")'; }
since() { python3 -c "print(f'{$(now) - $1:.1f}')"; }
k() { kctl "$C" "$@"; }
pod() { python3 scripts/pod.py "$@" | k apply -f - >/dev/null; }
events_of() { k get events --field-selector "involvedObject.name=$1" -o jsonpath='{range .items[*]}{.reason}: {.message}{"\n"}{end}'; }
trap 'kind_down "$C" >/dev/null 2>&1 || true' EXIT

kind_up "$C" config/kind.yaml
k wait --for=condition=Ready node --all --timeout=180s >/dev/null
docker exec "$C-control-plane" mkdir -p /opt/lab
docker cp src "$C-control-plane:/opt/lab/src" >/dev/null
k create configmap code --from-file=/opt/lab/src >/dev/null
for img in busybox python; do   # 预先拉取镜像，时间只反映调度与运行
  docker exec "$W" crictl pull "$(python3 scripts/pod.py image "$img")" >/dev/null
done

# 1. 调度只看 requests：按节点剩余可分配 CPU 放满 2 核的 Pod，再多放一个
alloc=$(k get node "$W" -o jsonpath='{.status.allocatable.cpu}')
used=$(k get pods -A --field-selector "spec.nodeName=$W" -o json | python3 -c '
import json, sys
def m(v): return int(v[:-1]) if v.endswith("m") else int(float(v) * 1000)
print(sum(m(c.get("resources", {}).get("requests", {}).get("cpu", "0")) for p in json.load(sys.stdin)["items"] for c in p["spec"]["containers"]))')
n=$(( (${alloc}000 - used) / 2000 + 1 ))
out sched.node "工作节点可分配 CPU ${alloc} 核，已被请求 ${used}m；创建 $n 个各请求 2 核、只执行 sleep 的 Pod"
for i in $(seq 1 "$n"); do pod "idle-$i" busybox '["sleep","3600"]' --req cpu=2 --label app=idle; done
sleep 15
out sched.pods "$(k get pods -l app=idle --no-headers | awk '{print $1"="$3}' | tr '\n' ' ')"
out sched.reason "$(events_of "idle-$n" | grep -m1 FailedScheduling)"
out sched.usage "此时工作节点容器的实际 CPU 占用：$(docker stats --no-stream --format '{{.CPUPerc}}' "$W")（占 Docker 虚拟机全部 CPU 的比例）"

# 2. 不写 requests 的 Pod 不受这个限制：节点已经「满了」，它照样被调度
pod besteffort busybox '["sleep","3600"]'
k wait --for=condition=Ready pod/besteffort --timeout=60s >/dev/null
out sched.besteffort "不写 requests 的 Pod：$(k get pod besteffort -o jsonpath='{.status.phase}，QoS {.status.qosClass}')"

# 3. 优先级抢占：高优先级 Pod 请求 2 核，调度器删除一个低优先级 Pod 为它腾出位置
cat <<'EOF' | k apply -f - >/dev/null
apiVersion: scheduling.k8s.io/v1
kind: PriorityClass
metadata: {name: urgent}
value: 1000
description: 实验用的高优先级
EOF
pod urgent busybox '["sleep","3600"]' --req cpu=2 --priority urgent
k wait --for=condition=Ready pod/urgent --timeout=90s >/dev/null
victim=$(k get events --field-selector reason=Preempted -o jsonpath='{.items[0].involvedObject.name}')
k wait --for=delete "pod/$victim" --timeout=60s >/dev/null
out preempt.result "urgent：$(k get pod urgent -o jsonpath='{.status.phase}')；被抢占的 Pod：$victim（$(k get events --field-selector reason=Preempted -o jsonpath='{.items[0].message}')）；剩余 idle Pod：$(k get pods -l app=idle --no-headers 2>/dev/null | awk '{print $1"="$3}' | tr '\n' ' ')"
k delete pod -l app=idle --wait=false >/dev/null; k delete pod urgent --wait=false >/dev/null

# 4. QoS 等级与内核 OOM 时的优先顺序
pod guaranteed busybox '["sleep","3600"]' --req cpu=100m,memory=64Mi --lim cpu=100m,memory=64Mi
pod burstable busybox '["sleep","3600"]' --req memory=64Mi
k wait --for=condition=Ready pod/guaranteed pod/burstable --timeout=60s >/dev/null
for p in guaranteed burstable besteffort; do
  out "qos.$p" "QoS $(k get pod "$p" -o jsonpath='{.status.qosClass}')，oom_score_adj=$(k exec "$p" -- cat /proc/1/oom_score_adj)"
done
k delete pod guaranteed burstable besteffort --wait=false >/dev/null

# 5. CPU limit：每个任务约 40ms CPU、间隔 200ms，平均占用远低于 limit，但单个任务超过每 100ms 周期的配额
pod calib python '["python","/code/burn.py","0","0"]' --restart Never
k wait --for=jsonpath='{.status.phase}'=Succeeded pod/calib --timeout=90s >/dev/null
K=$(k logs calib | python3 -c 'import sys; print(int(40 / float(sys.stdin.read().split("=")[1]) * 100000))')
out cpu.task "每个任务的循环次数 $K，任务之间空闲 200ms，每种 limit 跑 100 个任务"
for lim in none 1 500m 250m; do
  args=(--restart Never); [ "$lim" = none ] || args+=(--lim "cpu=$lim")
  pod "burn-$lim" python "[\"python\",\"/code/burn.py\",\"$K\",\"100\",\"200\"]" "${args[@]}"
  k wait --for=jsonpath='{.status.phase}'=Succeeded "pod/burn-$lim" --timeout=300s >/dev/null
  out "cpu.limit_$lim" "$(k logs "burn-$lim")"
done

# 6. 内存 limit：申请超过 limit 被内核杀掉，容器原地重启，反复几次后进入 CrashLoopBackOff
pod oom python '["python","-u","/code/mem.py","320"]' --lim memory=128Mi
pod fits python '["python","-u","/code/mem.py","320"]' --lim memory=512Mi
sleep 60
out oom.state "limit 128Mi 申请 320MB：$(k get pod oom -o json | python3 -c '
import json, sys
c = json.load(sys.stdin)["status"]["containerStatuses"][0]
state = next(iter(c["state"])); detail = c["state"][state].get("reason", "")
last = c["lastState"]["terminated"]
print("重启 {} 次，当前 {} {}，上一次退出：{} 退出码 {}".format(c["restartCount"], state, detail, last["reason"], last["exitCode"]))')"
out oom.fits "limit 512Mi 申请 320MB：$(k get pod fits -o jsonpath='{.status.phase}，重启 {.status.containerStatuses[0].restartCount} 次')"
k delete pod oom fits --wait=false >/dev/null

# 7. 临时存储超限：kubelet 驱逐整个 Pod，Pod 进入 Failed，不会原地重启
t=$(now)
pod disk busybox '["sh","-c","dd if=/dev/zero of=/tmp/fill bs=1M count=200 && echo written && sleep 3600"]' --lim ephemeral-storage=100Mi
until [ "$(k get pod disk -o jsonpath='{.status.phase}')" = Failed ]; do sleep 1; done
out evict.disk "创建后 $(since "$t") 秒：$(k get pod disk -o jsonpath='{.status.phase} {.status.reason}：{.status.message}')"
out evict.restart "被驱逐的 Pod 重启次数：$(k get pod disk -o jsonpath='{.status.containerStatuses[0].restartCount}')，容器退出码：$(k get pod disk -o jsonpath='{.status.containerStatuses[0].state.terminated.exitCode}')"

k version -o json | python3 -c 'import json,sys; v=json.load(sys.stdin); print("kubernetes_server:", v["serverVersion"]["gitVersion"])' >"$OUT/cluster-version.txt"
"$(kind_bin)" version >>"$OUT/cluster-version.txt"
cp config/kind.yaml "$OUT/kind-config.yaml"
write_environment "$OUT/environment.txt" "kind_node_image: $KIND_NODE_IMAGE"
cat "$f" >&2
expect_regex "$f" "^sched\.pods	(idle-[0-9]+=Running )+idle-[0-9]+=Pending $" "最后一个 Pod 因 requests 不足而 Pending"
expect_regex "$f" "^sched\.reason	FailedScheduling: .*Insufficient cpu" "调度失败原因：Insufficient cpu"
expect_line "$f" "sched.besteffort	不写 requests 的 Pod：Running，QoS BestEffort" "不写 requests 的 Pod 照样被调度"
expect_regex "$f" "^preempt\.result	urgent：Running；被抢占的 Pod：idle-[0-9]+（Preempted by pod .*）；剩余 idle Pod：idle-[0-9]+=Running idle-[0-9]+=Pending $" "高优先级 Pod 抢占一个低优先级 Pod"
expect_line "$f" "qos.guaranteed	QoS Guaranteed，oom_score_adj=-997" "Guaranteed 的 oom_score_adj 是 -997"
expect_regex "$f" "^qos\.burstable	QoS Burstable，oom_score_adj=9[0-9][0-9]$" "Burstable 按内存请求占节点容量的比例计算"
expect_line "$f" "qos.besteffort	QoS BestEffort，oom_score_adj=1000" "BestEffort 的 oom_score_adj 是 1000"
expect_regex "$f" "^oom\.state	.*上一次退出：OOMKilled 退出码 137$" "超过内存 limit 被 OOMKilled，退出码 137"
expect_regex "$f" "^oom\.fits	.*Running，重启 0 次$" "limit 足够时正常运行"
expect_regex "$f" "^evict\.disk	.*Failed Evicted：Pod ephemeral local storage usage exceeds the total limit of containers 100Mi" "临时存储超限：整个 Pod 被驱逐"
expect_regex "$f" "^evict\.restart	被驱逐的 Pod 重启次数：0" "被驱逐的 Pod 不会原地重启"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
r = {}
for lim in ["none", "1", "500m", "250m"]:
    m = re.search(rf"^cpu\.limit_{lim}\tp50=(\d+)ms p99=(\d+)ms 平均占用=([\d.]+)核 被限流周期=(\d+)/(\d+)", t, re.M)
    r[lim] = [float(x) for x in m.groups()]
assert r["none"][3] == 0 and r["1"][3] == 0, r                     # 配额足够时不限流
assert r["250m"][2] < 0.25 and r["500m"][2] < 0.5, r               # 平均占用低于 limit
assert r["250m"][3] > 0 and r["500m"][3] > 0, r                    # 仍然被限流
assert r["250m"][0] > 3 * r["none"][0] and r["500m"][0] > 1.3 * r["none"][0], r
oom = int(re.search(r"^oom\.state\t.*重启 (\d+) 次", t, re.M)[1])
assert oom >= 2, oom
print(f"通过：p50 不限 {r['none'][0]:.0f}ms、500m {r['500m'][0]:.0f}ms、250m {r['250m'][0]:.0f}ms；OOM 重启 {oom} 次")
PY
log "全部通过，输出在 $OUT"
