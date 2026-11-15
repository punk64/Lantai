import json
import os
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PLUGIN_ID = os.environ.get("LANTAI_PLUGIN_ID") or "sample.notify-digest"
PORT = int(os.environ.get("LANTAI_PLUGIN_PORT") or "26000")
HOST_KEY = os.environ.get("LANTAI_PLUGIN_HOST_KEY") or ""
TOKEN = os.environ.get("LANTAI_PLUGIN_TOKEN") or ""
BASE = (os.environ.get("LANTAI_PLUGIN_BASE") or "http://127.0.0.1:25600").rstrip("/")
DATA_DIR = os.environ.get("LANTAI_PLUGIN_DATA") or "data"
STARTED = time.time()


def load_settings():
    raw = os.environ.get("LANTAI_PLUGIN_SETTINGS") or "{}"
    try:
        value = json.loads(raw)
    except ValueError:
        return {}
    return value if isinstance(value, dict) else {}


SETTINGS = load_settings()

MANIFEST = {
    "id": PLUGIN_ID,
    "name": "通知与摘要示例",
    "version": "1.0.0",
    "protocol": 2,
    "minHost": "0.6.23",
    "description": "把宿主发来的通知写入本地日志，累加计数，并按 cron 每日汇总一次。",
    "capabilities": [
        {"name": "notify.send", "note": "把通知写入本地日志并累加计数"},
        {"name": "storage.blob", "note": "保存通知与新增书籍计数"},
        {"name": "task.schedule", "note": "每日汇总一次"},
        {"name": "event.observe", "note": "统计新增书籍"},
    ],
    "scopes": ["notify.send", "storage.blob", "task.schedule", "event.observe"],
    "tasks": [
        {"name": "dailyDigest", "cron": "0 9 * * *", "endpoint": "/v1/tasks/digest", "timeoutSeconds": 120}
    ],
    "config": {
        "type": "object",
        "properties": {
            "digestHour": {"type": "integer", "title": "汇总小时", "description": "仅作记录，真实调度以 cron 为准", "minimum": 0, "maximum": 23},
            "channel": {"type": "string", "title": "通知渠道", "enum": ["log", "kv"]},
            "loud": {"type": "boolean", "title": "重要通知"},
        },
    },
}


def append_log(name, payload):
    try:
        os.makedirs(DATA_DIR, exist_ok=True)
        with open(os.path.join(DATA_DIR, name), "a", encoding="utf-8") as handle:
            handle.write(json.dumps(payload, ensure_ascii=False) + "\n")
    except OSError:
        pass


def host_call(method, path, payload=None):
    if not TOKEN:
        return None
    body = None
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(BASE + path, data=body, method=method)
    request.add_header("X-API-Token", TOKEN)
    request.add_header("Accept", "application/json")
    if body is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            raw = response.read()
    except urllib.error.HTTPError as error:
        append_log("error.log", {"path": path, "status": error.code})
        return None
    except urllib.error.URLError as error:
        append_log("error.log", {"path": path, "reason": str(error.reason)})
        return None
    if not raw:
        return None
    try:
        return json.loads(raw)
    except ValueError:
        return None


def read_counter(key):
    current = host_call("GET", "/api/lantai/v1/plugin/kv/" + key)
    if not isinstance(current, dict):
        return 0
    try:
        return int(current.get("value") or 0)
    except (TypeError, ValueError):
        return 0


def bump_counter(key):
    value = read_counter(key) + 1
    host_call("PUT", "/api/lantai/v1/plugin/kv/" + key, {"value": str(value)})
    return value


def handle_event(name, data):
    if name == "reset":
        append_log("event.log", {"reset": data})
        return
    if name != "domain":
        return
    try:
        payload = json.loads(data)
    except ValueError:
        return
    kind = str(payload.get("type") or "")
    append_log("event.log", {"type": kind, "id": payload.get("id")})
    if kind == "book.added":
        bump_counter("book.count")


def watch_events():
    last_id = None
    while True:
        request = urllib.request.Request(BASE + "/api/lantai/v1/plugin/events", method="GET")
        request.add_header("X-API-Token", TOKEN)
        request.add_header("Accept", "text/event-stream")
        if last_id:
            request.add_header("Last-Event-ID", last_id)
        try:
            with urllib.request.urlopen(request) as response:
                event = None
                for raw in response:
                    line = raw.decode("utf-8", "replace").rstrip("\r\n")
                    if line.startswith("id:"):
                        last_id = line[3:].strip()
                    elif line.startswith("event:"):
                        event = line[6:].strip()
                    elif line.startswith("data:"):
                        handle_event(event, line[5:].strip())
        except Exception as error:
            append_log("error.log", {"stream": "events", "reason": str(error)})
        time.sleep(5)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        append_log("access.log", {"message": fmt % args})

    def do_GET(self):
        if not self.check_key():
            return
        if self.path == "/v1/manifest":
            self.reply(200, MANIFEST)
        elif self.path == "/v1/health":
            self.reply(200, {"status": "ok", "uptime": int(time.time() - STARTED)})
        else:
            self.reply(404, {"message": "未知路径"})

    def do_POST(self):
        if not self.check_key():
            return
        payload = self.read_json()
        if payload is None:
            return
        if self.path == "/v1/capabilities/notify.send":
            self.notify(payload)
        elif self.path == "/v1/tasks/digest":
            self.digest()
        else:
            self.reply(404, {"message": "未知路径"})

    def check_key(self):
        if HOST_KEY and self.headers.get("X-Lantai-Host-Key") != HOST_KEY:
            self.reply(401, {"message": "hostKey 校验失败"})
            return False
        return True

    def read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        try:
            value = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            self.reply(400, {"message": "请求体不是合法 JSON"})
            return None
        return value if isinstance(value, dict) else {}

    def notify(self, payload):
        title = str(payload.get("title") or "").strip()
        body = str(payload.get("body") or "").strip()
        level = str(payload.get("level") or "info")
        if not title or not body:
            self.reply(400, {"message": "title 与 body 必填"})
            return
        append_log("notify.log", {"title": title, "body": body, "level": level, "at": int(time.time())})
        count = bump_counter("notify.count")
        self.reply(200, {"accepted": True, "count": count})

    def digest(self):
        notify_count = read_counter("notify.count")
        book_count = read_counter("book.count")
        append_log("digest.log", {"notify": notify_count, "book": book_count, "at": int(time.time())})
        self.reply(200, {"notify": notify_count, "book": book_count})

    def reply(self, status, payload):
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


def main():
    if TOKEN:
        threading.Thread(target=watch_events, daemon=True).start()
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    append_log("plugin.log", {"event": "listening", "port": PORT, "plugin": PLUGIN_ID})
    server.serve_forever()


if __name__ == "__main__":
    main()
