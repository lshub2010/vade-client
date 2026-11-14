#!/usr/bin/env python3
"""Static server for the Compose Multiplatform wasmJs production bundle.

Why this exists instead of `python -m http.server`:
  * .wasm must be served as `application/wasm` (plain http.server serves it as
    application/octet-stream, which makes streaming compilation fail in some
    browsers and is the classic white-screen cause).
  * Compose/Skiko wasm may use SharedArrayBuffer; COOP/COEP headers are added so
    cross-origin isolation is available (harmless if unused).
  * Access log lines carry ms timestamps so first-request -> asset timings can be
    computed from the log (used for the first-paint lower-bound measurement).

Usage:
    python tools/wasm-serve.py <serve_dir> [port] [host]

Default: serves ./composeApp/build/dist/wasmJs/productionExecutable on
127.0.0.1:9173 (high port on purpose; 8080 & friends are avoided).
"""
import os
import sys
import time
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler

MIME = {
    ".wasm": "application/wasm",
    ".mjs": "text/javascript",
    ".js": "text/javascript",
    ".json": "application/json",
    ".html": "text/html; charset=utf-8",
    ".png": "image/png",
    ".ico": "image/x-icon",
    ".svg": "image/svg+xml",
    ".css": "text/css; charset=utf-8",
}

# Extra headers on every response.
HEADERS = [
    ("Cross-Origin-Opener-Policy", "same-origin"),
    ("Cross-Origin-Embedder-Policy", "require-corp"),
    ("Cross-Origin-Resource-Policy", "same-origin"),
]


class Handler(SimpleHTTPRequestHandler):
    extensions_map = {**SimpleHTTPRequestHandler.extensions_map, **MIME}

    def guess_type(self, path):
        ext = os.path.splitext(str(path))[1].lower()
        if ext in MIME:
            return MIME[ext]
        return super().guess_type(path)

    def end_headers(self):
        for name, value in HEADERS:
            self.send_header(name, value)
        super().end_headers()

    def log_message(self, fmt, *args):
        # ms-precision access log: "127.0.0.1 - GET /index.html 200 1234"
        sys.stdout.write(
            "%s | %s | %s\n"
            % (time.strftime("%H:%M:%S", time.localtime()) + ".%03d" % (int(time.time() * 1000) % 1000),
               self.address_string(), fmt % args)
        )
        sys.stdout.flush()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    serve_dir = os.path.abspath(sys.argv[1])
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 9173
    host = sys.argv[3] if len(sys.argv) > 3 else "127.0.0.1"
    if not os.path.isdir(serve_dir):
        print("serve_dir does not exist: %s" % serve_dir)
        sys.exit(2)
    os.chdir(serve_dir)
    srv = ThreadingHTTPServer((host, port), Handler)
    print("serving %s at http://%s:%d/ (COOP/COEP on, wasm MIME ok)" % (serve_dir, host, port))
    sys.stdout.flush()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
