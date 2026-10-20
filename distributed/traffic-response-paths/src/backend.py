import json, os, socketserver, struct, threading
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
counts={}; address='203.0.113.10'; role=os.environ.get('ROLE','good')
class DNS(socketserver.BaseRequestHandler):
    def handle(self):
        packet,sock=self.request
        header=packet[:2]+b'\x81\x80'+struct.pack('!HHHH',1,1,0,0)
        answer=b'\xc0\x0c'+struct.pack('!HHIH',1,1,5,4)+bytes(map(int,address.split('.')))
        sock.sendto(header+packet[12:]+answer,self.client_address)
class HTTP(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self): self.respond()
    def do_POST(self): self.respond()
    def respond(self):
        global address
        body=self.rfile.read(int(self.headers.get('Content-Length',0))).decode()
        if self.path=='/switch': address='203.0.113.20'
        counts[self.path]=counts.get(self.path,0)+1
        data={'role':role,'method':self.command,'body':body,'peer':self.client_address[0],
              'xff':self.headers.get('X-Forwarded-For'),'counts':dict(counts)}
        self.send_response(503 if role=='bad' and self.path.startswith('/retry') else 200)
        self.send_header('Content-Type','application/json'); self.end_headers()
        self.wfile.write(json.dumps(data).encode())
if role=='dns':
    threading.Thread(target=socketserver.UDPServer(('0.0.0.0',1053),DNS).serve_forever,daemon=True).start()
ThreadingHTTPServer(('0.0.0.0',8000),HTTP).serve_forever()
