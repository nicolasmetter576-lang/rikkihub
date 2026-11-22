#!/usr/bin/env python3
"""把卡文件交给真 SillyTavern 的服务端导入，再读回它存下的卡，记成 golden。
用法：ST_URL=http://127.0.0.1:18123 python3 st_import.py <out.json> <文件>...
文件按扩展名选 file_type（json / png / charx）。需要一个在运行的 ST（oracle.py start 会起一个）。"""
import json, os, sys, urllib.request, http.cookiejar, uuid

base = os.environ.get("ST_URL", "http://127.0.0.1:18123")
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
token = json.load(opener.open(base + "/csrf-token"))["token"]


def post(path, body, content_type="application/json"):
    req = urllib.request.Request(base + path, data=body, method="POST", headers={"X-CSRF-Token": token, "Content-Type": content_type})
    return opener.open(req).read()


def multipart(path, fields, filename, data):
    b = uuid.uuid4().hex
    parts = b"".join(f'--{b}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode() for k, v in fields.items())
    parts += f'--{b}\r\nContent-Disposition: form-data; name="avatar"; filename="{filename}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode() + data + f"\r\n--{b}--\r\n".encode()
    return post(path, parts, f"multipart/form-data; boundary={b}")


out = {}
for path in sys.argv[2:]:
    name = os.path.basename(path)
    ext = name.rsplit(".", 1)[-1].lower()
    resp = json.loads(multipart("/api/characters/import", {"file_type": ext}, name, open(path, "rb").read()))
    file_name = resp["file_name"]
    card = json.loads(post("/api/characters/get", json.dumps({"avatar_url": file_name + ".png"}).encode()))
    card.pop("shallow", None)
    out[name] = card
json.dump({"cases": out}, open(sys.argv[1], "w"), ensure_ascii=False, indent=1)
print(f"wrote {sys.argv[1]} ({len(out)} cards)")
