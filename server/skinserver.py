#!/usr/bin/env python3
"""Сервер скинов Enchanted (только стандартная библиотека Python 3.8+).

Запуск:  python3 skinserver.py [порт]      (по умолчанию 8787; скины лежат в папке ./skins)
Лаунчер заливает скин:  PUT /skin/<ник>   (заголовки X-Token, X-Model: classic|slim; тело - PNG 64x64)
Мод скачивает скин:     GET /skin/<ник>.png  (в ответе X-Model и ETag)
Ник «занимает» первый, кто залил скин: дальше нужен тот же X-Token (его лаунчер создаёт сам и хранит у себя).
"""
import hashlib, hmac, json, os, re, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "skins")
ROUTE = re.compile(r"^/skin/([A-Za-z0-9_]{3,16})(\.png)?$")
MAX = 64 * 1024
LOCK = threading.Lock()
LAST = {}  # ip -> время последней заливки (защита от флуда)
os.makedirs(DIR, exist_ok=True)


def paths(nick):
    n = nick.lower()
    return os.path.join(DIR, n + ".png"), os.path.join(DIR, n + ".json")


def valid_png(b):
    return (len(b) <= MAX and b[:8] == b"\x89PNG\r\n\x1a\n" and b[12:16] == b"IHDR"
            and int.from_bytes(b[16:20], "big") == 64 and int.from_bytes(b[20:24], "big") == 64)


class H(BaseHTTPRequestHandler):
    server_version = "EnchantedSkins/1"

    def log_message(self, *a):
        pass

    def reply(self, code, body=b"", ctype="text/plain", headers=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if body and self.command != "HEAD":
            self.wfile.write(body)

    def nick(self):
        m = ROUTE.match(self.path.split("?")[0])
        return m.group(1) if m else None

    def do_GET(self):
        if self.path == "/":
            return self.reply(200, b"enchanted skins ok")
        nick = self.nick()
        if not nick:
            return self.reply(404)
        png, meta = paths(nick)
        try:
            data = open(png, "rb").read()
            model = json.load(open(meta, encoding="utf-8")).get("model", "classic")
        except OSError:
            return self.reply(404)
        etag = '"%s"' % hashlib.sha1(data + model.encode()).hexdigest()
        if self.headers.get("If-None-Match") == etag:
            return self.reply(304, headers={"ETag": etag})
        self.reply(200, data, "image/png", {"X-Model": model, "ETag": etag, "Cache-Control": "max-age=60"})

    def auth(self, meta):
        token = self.headers.get("X-Token", "")
        if not 16 <= len(token) <= 128:
            return None
        th = hashlib.sha256(token.encode()).hexdigest()
        if os.path.exists(meta):
            try:
                stored = json.load(open(meta, encoding="utf-8")).get("token", "")
            except (OSError, ValueError):
                stored = ""
            if not hmac.compare_digest(stored, th):
                return None
        return th

    def do_PUT(self):
        nick = self.nick()
        if not nick:
            return self.reply(404)
        ip = self.client_address[0]
        now = time.time()
        if now - LAST.get(ip, 0) < 3:
            return self.reply(429)
        LAST[ip] = now
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if n <= 0 or n > MAX:
            return self.reply(413)
        data = self.rfile.read(n)
        if not valid_png(data):
            return self.reply(400, "нужен PNG 64x64".encode())
        png, meta = paths(nick)
        with LOCK:
            th = self.auth(meta)
            if th is None:
                return self.reply(403, "ник занят другим токеном".encode())
            model = "slim" if self.headers.get("X-Model") == "slim" else "classic"
            open(png, "wb").write(data)
            json.dump({"token": th, "model": model}, open(meta, "w", encoding="utf-8"))
        self.reply(200, b"ok")

    def do_DELETE(self):
        nick = self.nick()
        if not nick:
            return self.reply(404)
        png, meta = paths(nick)
        with LOCK:
            if self.auth(meta) is None:
                return self.reply(403)
            for p in (png, meta):
                try:
                    os.remove(p)
                except OSError:
                    pass
        self.reply(200, b"ok")


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8787
    print("skins server on :%d, dir=%s" % (port, DIR))
    ThreadingHTTPServer(("0.0.0.0", port), H).serve_forever()
