import json, secrets, threading
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from playwright.sync_api import sync_playwright
site=Path('build/site'); expected=(site/'value.txt').read_text()
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        nonce=secrets.token_urlsafe(24)
        self.send_response(200)
        self.send_header('Content-Type','text/html; charset=utf-8')
        if self.path=='/csp':
            self.send_header('Content-Security-Policy',f"default-src 'none'; script-src 'nonce-{nonce}'")
        if self.path=='/cookies':
            self.send_header('Set-Cookie','hidden=demo; HttpOnly; SameSite=Strict; Path=/')
            self.send_header('Set-Cookie','visible=demo; SameSite=Lax; Path=/')
        self.end_headers()
        if self.path=='/template': body=(site/'template.html').read_text()
        elif self.path in ('/csp','/control'):
            body=f'<script>window.plain=true</script><script nonce="{nonce}">window.authorized=true</script>'
        elif self.path=='/echo': body=json.dumps({'cookie':self.headers.get('Cookie','')})
        else: body='<a id="go" href="http://127.0.0.1:%s/echo">continue</a>'%self.server.server_port
        self.wfile.write(body.encode())
server=ThreadingHTTPServer(('0.0.0.0',0),Handler)
threading.Thread(target=server.serve_forever,daemon=True).start(); port=server.server_port
base=f'http://127.0.0.1:{port}'
results={}
with sync_playwright() as p:
    browser=p.chromium.launch(args=['--no-sandbox'])
    ctx=browser.new_context(); page=ctx.new_page()
    page.goto(base+'/template')
    results['text_equal']=page.locator('#text').inner_text()==expected
    results['attribute_equal']=page.locator('#attribute').get_attribute('title')==expected
    results['javascript_equal']=page.evaluate('window.safeValue')==expected
    results['html_only_javascript_equal']=page.evaluate('window.htmlOnly')==expected
    results['unquoted_title']=page.locator('#unquoted').get_attribute('title')
    results['unquoted_extra_attribute']=page.locator('#unquoted').evaluate('(e)=>e.hasAttribute("beta")')
    results['url_protocol']=page.locator('#url').evaluate('(e)=>new URL(e.href).protocol')
    assert results['text_equal'] and results['attribute_equal'] and results['javascript_equal']
    assert not results['html_only_javascript_equal']
    assert results['unquoted_title']=='alpha' and results['unquoted_extra_attribute']
    assert results['url_protocol']=='javascript:'
    page.goto(base+'/control'); results['control']=page.evaluate('({plain:!!window.plain, authorized:!!window.authorized})')
    page.goto(base+'/csp'); results['csp']=page.evaluate('({plain:!!window.plain, authorized:!!window.authorized})')
    assert results['control']=={'plain':True,'authorized':True}
    assert results['csp']=={'plain':False,'authorized':True}
    page.goto(base+'/cookies'); results['document_cookie']=page.evaluate('document.cookie')
    results['same_site_cookie']=json.loads(page.evaluate("fetch('/echo').then(r=>r.text())"))['cookie']
    assert 'hidden=' not in results['document_cookie'] and 'visible=' in results['document_cookie']
    assert 'hidden=' in results['same_site_cookie']
    page.goto(f'http://localhost:{port}/start'); page.locator('#go').click(); page.wait_for_url(base+'/echo')
    results['cross_site_top_get']=json.loads(page.locator('body').inner_text())['cookie']
    assert 'hidden=' not in results['cross_site_top_get'] and 'visible=' in results['cross_site_top_get']
    results['chromium']=browser.version
    browser.close()
server.shutdown();print(json.dumps(results,ensure_ascii=False,indent=2))
