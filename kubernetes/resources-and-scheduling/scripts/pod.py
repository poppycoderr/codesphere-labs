"""生成实验用的 Pod（JSON）：pod.py image <busybox|python> 输出镜像；pod.py <名字> <busybox|python> <命令 JSON> [--req cpu=..,memory=..] [--lim ...] [--restart Never] [--priority 类名]"""
import argparse
import json
import sys

IMAGES = {
    "busybox": "busybox:1.37@sha256:bdf57e528e45e4433820e045b29b4597825a1c9e38353532d90a01445013f82e",
    "python": "python:3.14.7-slim@sha256:51dafde81dbdb6ebde285137a295cf18a47ca95234fe388a343719cb97305b3d",
}
if sys.argv[1:2] == ["image"]:                 # pod.py image <busybox|python>：只输出镜像
    print(IMAGES[sys.argv[2]])
    sys.exit()
p = argparse.ArgumentParser()
p.add_argument("name")
p.add_argument("image", choices=IMAGES)
p.add_argument("command")
p.add_argument("--req", default="")
p.add_argument("--lim", default="")
p.add_argument("--restart", default="Always")
p.add_argument("--priority", default="")
p.add_argument("--label", default="")
a = p.parse_args()
kv = lambda s: dict(x.split("=", 1) for x in s.split(",") if x)
res = {k: v for k, v in (("requests", kv(a.req)), ("limits", kv(a.lim))) if v}
spec = {"restartPolicy": a.restart, "terminationGracePeriodSeconds": 1,
        "volumes": [{"name": "code", "configMap": {"name": "code"}}],
        "containers": [{"name": "c", "image": IMAGES[a.image], "command": json.loads(a.command), "resources": res,
                        "volumeMounts": [{"name": "code", "mountPath": "/code"}]}]}
if a.priority:
    spec["priorityClassName"] = a.priority
meta = {"name": a.name}
if a.label:
    meta["labels"] = kv(a.label)
print(json.dumps({"apiVersion": "v1", "kind": "Pod", "metadata": meta, "spec": spec}))
