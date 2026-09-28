"""生成实验用的 Deployment、Service 与客户端 Pod（JSON 形式，kubectl 可直接应用）。"""
import argparse
import json

PYTHON = "python:3.14.7-slim@sha256:51dafde81dbdb6ebde285137a295cf18a47ca95234fe388a343719cb97305b3d"

p = argparse.ArgumentParser()
p.add_argument("kind", choices=["deployment", "service", "client"])
p.add_argument("--name", default="web")
p.add_argument("--replicas", type=int, default=3)
p.add_argument("--env", action="append", default=[])
p.add_argument("--readiness", default="")
p.add_argument("--liveness", default="")
p.add_argument("--startup", default="")
p.add_argument("--label", default="v1")
p.add_argument("--surge", default="25%")
p.add_argument("--unavailable", default="25%")
p.add_argument("--deadline", type=int, default=600)
p.add_argument("--prestop", type=int, default=0)
p.add_argument("--duration", type=int, default=30)
a = p.parse_args()


def probe(path, period=1, failures=3):
    return {"httpGet": {"path": path, "port": 8080}, "periodSeconds": period, "failureThreshold": failures, "timeoutSeconds": 1}


def num(v):
    return v if v.endswith("%") else int(v)


code = {"name": "code", "configMap": {"name": "code"}}
if a.kind == "service":
    out = {"apiVersion": "v1", "kind": "Service", "metadata": {"name": a.name},
           "spec": {"selector": {"app": a.name}, "ports": [{"port": 80, "targetPort": 8080}]}}
elif a.kind == "client":
    out = {"apiVersion": "v1", "kind": "Pod", "metadata": {"name": a.name},
           "spec": {"restartPolicy": "Never", "volumes": [code],
                    "containers": [{"name": "client", "image": PYTHON, "command": ["python", "-u", "/code/client.py", "web", str(a.duration)],
                                    "volumeMounts": [{"name": "code", "mountPath": "/code"}]}]}}
else:
    c = {"name": "app", "image": PYTHON, "command": ["python", "-u", "/code/app.py"],
         "env": [{"name": k, "value": v} for k, v in (e.split("=", 1) for e in a.env)],
         "ports": [{"containerPort": 8080}], "volumeMounts": [{"name": "code", "mountPath": "/code"}]}
    if a.readiness:
        c["readinessProbe"] = probe(a.readiness)
    if a.liveness:
        c["livenessProbe"] = probe(a.liveness)
    if a.startup:
        c["startupProbe"] = probe(a.startup, failures=30)
    if a.prestop:
        c["lifecycle"] = {"preStop": {"sleep": {"seconds": a.prestop}}}
    out = {"apiVersion": "apps/v1", "kind": "Deployment", "metadata": {"name": a.name},
           "spec": {"replicas": a.replicas, "progressDeadlineSeconds": a.deadline, "selector": {"matchLabels": {"app": a.name}},
                    "strategy": {"type": "RollingUpdate", "rollingUpdate": {"maxSurge": num(a.surge), "maxUnavailable": num(a.unavailable)}},
                    "template": {"metadata": {"labels": {"app": a.name, "ver": a.label}},
                                 "spec": {"terminationGracePeriodSeconds": 30, "volumes": [code], "containers": [c]}}}}
print(json.dumps(out))
