"""Dev harness server: static files, plus /data/* proxied to the gateway with the API token
added here (so the token never reaches the browser). Bound to 127.0.0.1 only.

usage: python3 serve.py <dir> <port> <gateway-url> <api-token-file>
"""
import sys
import urllib.error
import urllib.request
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

root, port, gateway, token_file = sys.argv[1], int(sys.argv[2]), sys.argv[3].rstrip("/"), sys.argv[4]
TOKEN = open(token_file).read().strip()


class Handler(SimpleHTTPRequestHandler):
    def _proxy(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        req = urllib.request.Request(gateway + self.path, data=body, method=self.command)
        for h in ("Content-Type", "Accept", "Range"):
            if self.headers.get(h):
                req.add_header(h, self.headers[h])
        req.add_header("X-Ignition-API-Token", TOKEN)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                status, headers, data = r.status, r.headers, r.read()
        except urllib.error.HTTPError as e:
            status, headers, data = e.code, e.headers, e.read()
        self.send_response(status)
        for h in ("Content-Type", "Content-Range", "Accept-Ranges"):
            if headers.get(h):
                self.send_header(h, headers[h])
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        return self._proxy() if self.path.startswith("/data/") else super().do_GET()

    def do_POST(self):
        return self._proxy()

    do_PUT = do_POST
    do_DELETE = do_POST


ThreadingHTTPServer(("127.0.0.1", port), partial(Handler, directory=root)).serve_forever()
