#!/usr/bin/env python3
"""Smoke-test a running rikkihub web server (release builds especially).

Stdlib only. Point it at a server reachable from this machine, e.g. after
`adb forward tcp:18383 tcp:18383` with the web server started in the app:

    python3 scripts/web_smoke.py http://127.0.0.1:18383
    python3 scripts/web_smoke.py https://127.0.0.1:18383          # self-signed: not verified
    python3 scripts/web_smoke.py --password PASS http://127.0.0.1:18383

Checks, each on fresh TCP connections, in the order the 2.6.0-2.8.1 release
breakage would surface:

1. index page serves (engine starts and accepts);
2. N parallel /api/health requests all succeed (shared @Sharable initializer);
3. /api/events answers as an SSE stream;
4. a WebSocket upgrade (terminal route, bogus session) answers 101 and the
   server's close frame arrives (Ktor's NettyDirectEncoder after upgrade).

Checks 3 and 4 need the API to be reachable: web auth off, or --password
(then the WebSocket check first gets a ticket from /api/auth/ticket).
Exit code 0 when everything passes, 1 otherwise.
"""

from __future__ import annotations

import argparse
import base64
import http.client
import json
import os
import socket
import ssl
import sys
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlsplit

TIMEOUT = 15


class Target:
    def __init__(self, base_url, password=None):
        parts = urlsplit(base_url)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            raise SystemExit("base url must be http(s)://host:port")
        self.scheme = parts.scheme
        self.host = parts.hostname
        self.port = parts.port or (443 if self.scheme == "https" else 80)
        self.origin = "%s://%s:%d" % (self.scheme, self.host, self.port)
        self.headers = {"Origin": self.origin}
        if password is not None:
            self.headers["Cookie"] = self._login(password)

    def ssl_context(self):
        context = ssl.create_default_context()
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE  # local self-signed CA
        return context

    def connection(self):
        if self.scheme == "https":
            return http.client.HTTPSConnection(self.host, self.port, timeout=TIMEOUT, context=self.ssl_context())
        return http.client.HTTPConnection(self.host, self.port, timeout=TIMEOUT)

    def request(self, method, path, body=None, headers=None, read=True):
        conn = self.connection()
        try:
            merged = dict(self.headers, Connection="close", **(headers or {}))
            conn.request(method, path, body=body, headers=merged)
            response = conn.getresponse()
            data = response.read() if read else response.read(256)
            return response.status, response.getheader("Content-Type", ""), data, response.getheader("Set-Cookie")
        finally:
            conn.close()

    def _login(self, password):
        status, _, body, cookie = self.request(
            "POST", "/api/auth/token", body=json.dumps({"password": password}),
            headers={"Content-Type": "application/json"})
        if status != 200 or not cookie:
            raise SystemExit("login failed: HTTP %d %s" % (status, body[:200]))
        return cookie.split(";", 1)[0]

    def raw_socket(self):
        sock = socket.create_connection((self.host, self.port), timeout=TIMEOUT)
        if self.scheme == "https":
            sock = self.ssl_context().wrap_socket(sock, server_hostname=self.host)
        return sock


def check_index(target):
    status, ctype, body, _ = target.request("GET", "/")
    ok = status == 200 and b"<html" in body.lower()
    return ok, "GET / -> %d %s (%d bytes)" % (status, ctype, len(body))


def check_parallel_health(target, count):
    with ThreadPoolExecutor(max_workers=min(count, 16)) as pool:
        results = list(pool.map(lambda _: _status_or_error(target, "/api/health"), range(count)))
    failures = [r for r in results if r != 200]
    return not failures, "%d parallel /api/health -> %d ok, failures: %s" % (
        count, count - len(failures), failures[:5] or "none")


def _status_or_error(target, path):
    try:
        return target.request("GET", path)[0]
    except (OSError, http.client.HTTPException) as exc:
        return "%s: %s" % (type(exc).__name__, exc)


def check_sse(target):
    status, ctype, head, _ = target.request("GET", "/api/events", headers={"Accept": "text/event-stream"}, read=False)
    ok = status == 200 and ctype.startswith("text/event-stream")
    return ok, "GET /api/events -> %d %s" % (status, ctype)


def check_websocket(target):
    path = "/api/workspaces/smoke/terminal/sessions/smoke"
    if "Cookie" in target.headers:
        # Terminal sockets take a single-use ?ticket=, not the session cookie.
        status, _, body, _ = target.request(
            "POST", "/api/auth/ticket", body=json.dumps({"scope": "ws", "target": path}),
            headers={"Content-Type": "application/json"})
        if status != 200:
            return False, "POST /api/auth/ticket -> %d %s" % (status, body[:200])
        path += "?ticket=" + json.loads(body)["ticket"]
    key = base64.b64encode(os.urandom(16)).decode()
    lines = [
        "GET %s HTTP/1.1" % path,
        "Host: %s:%d" % (target.host, target.port),
        "Upgrade: websocket",
        "Connection: Upgrade",
        "Sec-WebSocket-Key: %s" % key,
        "Sec-WebSocket-Version: 13",
    ] + ["%s: %s" % item for item in target.headers.items()]
    sock = target.raw_socket()
    try:
        sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode())
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = sock.recv(4096)
            if not chunk:
                break
            data += chunk
        head, _, rest = data.partition(b"\r\n\r\n")
        status_line = head.split(b"\r\n", 1)[0].decode(errors="replace")
        if b" 101 " not in head.split(b"\r\n", 1)[0] + b" ":
            return False, "WS upgrade -> %s" % status_line
        frame = rest
        while len(frame) < 2:
            chunk = sock.recv(4096)
            if not chunk:
                break
            frame += chunk
        if len(frame) < 2:
            return False, "WS upgrade -> 101 but connection closed without a frame"
        opcode, length = frame[0] & 0x0F, frame[1] & 0x7F
        while len(frame) < 2 + length:
            chunk = sock.recv(4096)
            if not chunk:
                break
            frame += chunk
        payload = frame[2:2 + length]
        reason = payload[2:].decode(errors="replace") if opcode == 0x8 else ""
        ok = opcode == 0x8 and "not found" in reason.lower()
        return ok, "WS upgrade -> 101, first frame opcode %d, close reason %r" % (opcode, reason)
    except (OSError, ssl.SSLError) as exc:
        return False, "WS upgrade failed: %s: %s" % (type(exc).__name__, exc)
    finally:
        sock.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description="Smoke-test a running rikkihub web server.")
    parser.add_argument("base_url", help="e.g. http://127.0.0.1:18383")
    parser.add_argument("--password", help="web access password, when web auth is on")
    parser.add_argument("--parallel", type=int, default=24, help="parallel /api/health requests")
    args = parser.parse_args(argv)

    target = Target(args.base_url, args.password)
    checks = [
        ("index", lambda: check_index(target)),
        ("parallel connections", lambda: check_parallel_health(target, args.parallel)),
        ("sse", lambda: check_sse(target)),
        ("websocket upgrade", lambda: check_websocket(target)),
    ]
    all_ok = True
    for name, run in checks:
        try:
            ok, detail = run()
        except (OSError, http.client.HTTPException, ssl.SSLError) as exc:
            ok, detail = False, "%s: %s" % (type(exc).__name__, exc)
        all_ok &= ok
        print("%s %-21s %s" % ("OK  " if ok else "FAIL", name, detail))
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
