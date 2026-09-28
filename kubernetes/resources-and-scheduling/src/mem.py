"""每次申请 16MB 并写满，直到 MB 参数指定的总量，然后保持运行。"""
import sys
import time

chunks = []
for i in range(int(sys.argv[1]) // 16):
    chunks.append(bytearray(16 * 1024 * 1024))
    print(f"allocated {(i + 1) * 16}MB", flush=True)
time.sleep(3600)
