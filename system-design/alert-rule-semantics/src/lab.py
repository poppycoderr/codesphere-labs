"""告警规则在数什么：increase 的外推、for 过滤短暂尖峰、pending 到 firing、keep_firing_for、数据断档与 absent、
Alertmanager 的分组、静默与通知失败。本进程同时是被抓取的指标端点（:8000）与两个 webhook 接收器（:9000）。
只用标准库。输出 TSV（键、说明），时间相关的结论只断言范围。"""
import json
import sys
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PROM = "http://prometheus:9090"
AM = "http://alertmanager:9093"
counters = {"spike": 0, "sustained": 0, "gap": 0}
lock = threading.Lock()
received = {"ok": [], "broken": []}


class Metrics(BaseHTTPRequestHandler):
    def do_GET(self):
        with lock:
            body = "# TYPE app_errors_total counter\n" + "".join(
                f'app_errors_total{{scenario="{k}"}} {v}\n' for k, v in counters.items())
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4")
        self.end_headers()
        self.wfile.write(body.encode())

    def log_message(self, *a):
        pass


class Hooks(BaseHTTPRequestHandler):
    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        name = self.path.strip("/")
        received[name].append((time.time(), sorted(a["labels"]["alertname"] for a in data["alerts"])))
        self.send_response(500 if name == "broken" else 200)
        self.end_headers()

    def log_message(self, *a):
        pass


def get(url):
    with urllib.request.urlopen(url, timeout=5) as r:
        return json.loads(r.read())


def query(expr):
    r = get(PROM + "/api/v1/query?query=" + urllib.parse.quote(expr))["data"]["result"]
    return float(r[0]["value"][1]) if r else None


def alerts():
    return {a["labels"]["alertname"]: a["state"] for a in get(PROM + "/api/v1/alerts")["data"]["alerts"]}


def out(k, v):
    print(f"{k}\t{v}", flush=True)


def watch(seconds, names, extra=None):
    """每 0.25 秒记录一次各告警的状态，返回 [(相对时间, {名字: 状态})]。"""
    t0, log = time.time(), []
    while time.time() - t0 < seconds:
        a = alerts()
        log.append((time.time() - t0, {n: a.get(n, "inactive") for n in names}))
        if extra:
            extra()
        time.sleep(0.25)
    return log


def first(log, name, state):
    return next((t for t, s in log if s[name] == state), None)


def last(log, name, state):
    return max((t for t, s in log if s[name] == state), default=None)


def main():
    threading.Thread(target=ThreadingHTTPServer(("0.0.0.0", 8000), Metrics).serve_forever, daemon=True).start()
    threading.Thread(target=ThreadingHTTPServer(("0.0.0.0", 9000), Hooks).serve_forever, daemon=True).start()
    for _ in range(120):
        try:
            if query('up{job="lab"}') == 1 and len(get(PROM + "/api/v1/rules")["data"]["groups"]) == 1:
                break
        except Exception:
            pass
        time.sleep(0.5)
    time.sleep(4)
    info = get(PROM + "/api/v1/status/buildinfo")["data"]["version"]
    am = get(AM + "/api/v2/status")["versionInfo"]["version"]
    out("env", f"prometheus={info}，alertmanager={am}，抓取与评估间隔 1 秒")

    # 静默 SpikeNoFor：它仍会在 Prometheus 里 firing，但不会发出通知
    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    end = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 600))
    req = urllib.request.Request(AM + "/api/v2/silences", method="POST", headers={"Content-Type": "application/json"},
                                 data=json.dumps({"matchers": [{"name": "alertname", "value": "SpikeNoFor", "isRegex": False}],
                                                  "startsAt": now, "endsAt": end, "createdBy": "lab", "comment": "lab"}).encode())
    urllib.request.urlopen(req).read()

    # 一、尖峰：一次 +8，之后不再增加
    peak = []
    suppressed = []
    with lock:
        counters["spike"] += 8
    def sample():
        v = query('increase(app_errors_total{scenario="spike"}[3s])')
        if v is not None:
            peak.append(v)
        for a in get(AM + "/api/v2/alerts"):
            if a["labels"]["alertname"] == "SpikeNoFor":
                suppressed.append(a["status"]["state"])
    log = watch(10, ["SpikeNoFor", "SpikeFor5s"], sample)
    out("spike.increase", f"计数器只增加了 8，increase(...[3s]) 的最大值大于 8（外推到窗口边界）={max(peak) > 8}")
    out("spike.nofor", f"没有 for 的规则进入 firing={first(log, 'SpikeNoFor', 'firing') is not None}")
    out("spike.for", f"for: 5s 的规则：进入过 pending={first(log, 'SpikeFor5s', 'pending') is not None}，进入过 firing={first(log, 'SpikeFor5s', 'firing') is not None}")
    out("silence", f"被静默的 SpikeNoFor 在 Alertmanager 里的状态是 suppressed={'suppressed' in suppressed}")
    details = {"spike_increase_max": max(peak)}

    # 二、持续错误：每秒 +3，持续 14 秒后停止
    t0 = time.time()
    ticker_stop = threading.Event()
    def ticker():
        while not ticker_stop.is_set():
            with lock:
                if time.time() - t0 < 14:
                    counters["sustained"] += 3
            time.sleep(1)
    threading.Thread(target=ticker, daemon=True).start()
    log = watch(32, ["SustainedFor5s", "SustainedKeep"])
    ticker_stop.set()
    p, f = first(log, "SustainedFor5s", "pending"), first(log, "SustainedFor5s", "firing")
    end_plain = last(log, "SustainedFor5s", "firing") - 14
    end_keep = last(log, "SustainedKeep", "firing") - 14
    details.update(pending_to_firing=round(f - p, 2), plain_after_stop=round(end_plain, 2), keep_after_stop=round(end_keep, 2))
    out("sustained.for", f"for: 5s 从 pending 到 firing 用了 4 到 7 秒={4 <= f - p <= 7}")
    out("sustained.keep", f"错误停止后，keep_firing_for: 8s 的规则比普通规则多 firing 6 到 10 秒={6 <= end_keep - end_plain <= 10}")

    # 三、数据断档：每秒 +3 持续 6 秒，然后这个序列从指标端点消失
    for _ in range(6):
        with lock:
            counters["gap"] += 3
        time.sleep(1)
    before = alerts().get("GapErrors", "inactive")
    with lock:
        del counters["gap"]
    log = watch(12, ["GapErrors", "GapAbsent"])
    out("gap.before", f"断档前 GapErrors 的状态：{before}")
    out("gap.after", f"序列消失后 GapErrors 回到 inactive={log[-1][1]['GapErrors'] == 'inactive'}，absent 规则进入 firing={first(log, 'GapAbsent', 'firing') is not None}")

    # 四、通知：分组与失败
    time.sleep(6)
    ok = received["ok"]
    groups = [names for _, names in ok]
    out("notify.group", f"team=a 的 webhook 收到的通知：{groups}")
    m = urllib.request.urlopen(AM + "/metrics").read().decode()
    failed = sum(float(l.split()[-1]) for l in m.splitlines() if l.startswith("alertmanager_notifications_failed_total") and 'integration="webhook"' in l)
    details.update(broken_attempts=len(received["broken"]), webhook_failed_total=failed)
    out("notify.broken", f"team=b 的 webhook 一直返回 500：收到过请求={len(received['broken']) > 0}，alertmanager_notifications_failed_total 大于 0={failed > 0}，GapAbsent 在 Prometheus 里仍是 firing={alerts().get('GapAbsent') == 'firing'}")
    print(json.dumps(details), file=sys.stderr)


if __name__ == "__main__":
    main()
