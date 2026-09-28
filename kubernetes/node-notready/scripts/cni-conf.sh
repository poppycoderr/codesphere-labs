#!/usr/bin/env bash
# cni-conf.sh <节点容器> <podCIDR>：在节点上写入 ptp + host-local 的 CNI 配置（kind 节点镜像自带这两个插件）
set -euo pipefail
docker exec -i "$1" sh -c 'mkdir -p /etc/cni/net.d && cat > /etc/cni/net.d/10-lab.conflist' <<JSON
{
  "cniVersion": "1.0.0",
  "name": "lab",
  "plugins": [
    {"type": "ptp", "mtu": 1500,
     "ipam": {"type": "host-local", "ranges": [[{"subnet": "$2"}]], "routes": [{"dst": "0.0.0.0/0"}]}},
    {"type": "portmap", "capabilities": {"portMappings": true}}
  ]
}
JSON
