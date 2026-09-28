"""从 kubectl get pods -o json 输出未处于删除中的 Pod 的 phase/ready。"""
import json
import sys

items = json.load(sys.stdin)["items"]
print("".join("{}/{} ".format(p["status"]["phase"], str(p["status"]["containerStatuses"][0]["ready"]).lower())
              for p in items if not p["metadata"].get("deletionTimestamp")))
