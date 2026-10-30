"""最小的格式化语言服务：只实现 initialize、文档同步（全量）与 textDocument/formatting。
用法：fmt_server.py <缩进空格数> [响应延迟毫秒]。格式化结果把每行开头的空白统一成这么多空格一级（按原来的制表符或 2 个空格计一级）。"""
import json
import sys
import time

INDENT = int(sys.argv[1])
DELAY = int(sys.argv[2]) / 1000 if len(sys.argv) > 2 else 0
docs = {}


def read():
    length = 0
    while True:
        line = sys.stdin.buffer.readline()
        if not line:
            sys.exit(0)
        line = line.decode().strip()
        if not line:
            break
        if line.lower().startswith("content-length:"):
            length = int(line.split(":")[1])
    return json.loads(sys.stdin.buffer.read(length))


def send(msg):
    body = json.dumps(msg).encode()
    sys.stdout.buffer.write(f"Content-Length: {len(body)}\r\n\r\n".encode() + body)
    sys.stdout.buffer.flush()


def fmt(text):
    out = []
    for line in text.split("\n"):
        stripped = line.lstrip(" \t")
        ws = line[: len(line) - len(stripped)]
        level = ws.count("\t") + ws.count(" ") // 2
        out.append(" " * (INDENT * level) + stripped)
    return "\n".join(out)


while True:
    m = read()
    method = m.get("method")
    if method == "initialize":
        send({"jsonrpc": "2.0", "id": m["id"], "result": {"capabilities": {"textDocumentSync": 1, "documentFormattingProvider": True}}})
    elif method == "textDocument/didOpen":
        d = m["params"]["textDocument"]
        docs[d["uri"]] = d["text"]
    elif method == "textDocument/didChange":
        docs[m["params"]["textDocument"]["uri"]] = m["params"]["contentChanges"][-1]["text"]
    elif method == "textDocument/formatting":
        time.sleep(DELAY)
        text = docs[m["params"]["textDocument"]["uri"]]
        lines = text.split("\n")
        new = fmt(text)
        edit = {"range": {"start": {"line": 0, "character": 0}, "end": {"line": len(lines), "character": 0}}, "newText": new if new.endswith("\n") else new + "\n"}
        send({"jsonrpc": "2.0", "id": m["id"], "result": [edit] if new != text else []})
    elif method == "shutdown":
        send({"jsonrpc": "2.0", "id": m["id"], "result": None})
    elif method == "exit":
        sys.exit(0)
    elif "id" in m and method:
        send({"jsonrpc": "2.0", "id": m["id"], "result": None})
