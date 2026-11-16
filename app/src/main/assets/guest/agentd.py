#!/usr/bin/env python3
"""LinuxXR agentd: lets cloud AI agents see and drive the LinuxXR desktops.

MCP (Streamable HTTP, JSON responses) on POST /mcp, bound to 127.0.0.1 only.
Every request needs `Authorization: Bearer <token>` (token in /etc/linuxxr/agent-token).
Reach it from the cloud through `linuxxr tunnel user@host` (SSH -R).
"""
import base64
import glob
import hmac
import io
import json
import os
import subprocess
import sys
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = os.environ.get("LINUXXR_AGENTD_HOST", "127.0.0.1")
PORT = int(os.environ.get("LINUXXR_AGENTD_PORT", "7777"))
TOKEN_FILE = os.environ.get("LINUXXR_AGENTD_TOKEN_FILE", "/etc/linuxxr/agent-token")
PROTOCOL_VERSIONS = ("2025-06-18", "2025-03-26", "2024-11-05")
MAX_OUTPUT = 100_000

DISPLAY_PROP = {"type": "string", "description": "X display, e.g. ':1'", "default": ":1"}
XY = {"x": {"type": "integer"}, "y": {"type": "integer"}}


def schema(props, required=()):
    return {"type": "object", "properties": props, "required": list(required)}


TOOLS = [
    {"name": "list_displays", "description": "List LinuxXR X displays and their sizes.",
     "inputSchema": schema({})},
    {"name": "screenshot", "description": "PNG screenshot of a display. Coordinates of other tools use this image's pixels unless max_width scaled it (scale is reported).",
     "inputSchema": schema({"display": DISPLAY_PROP, "max_width": {"type": "integer", "description": "Downscale if wider"}})},
    {"name": "click", "description": "Move the pointer and click. button: 1 left, 2 middle, 3 right.",
     "inputSchema": schema({**XY, "button": {"type": "integer", "default": 1}, "count": {"type": "integer", "default": 1}, "display": DISPLAY_PROP}, ("x", "y"))},
    {"name": "move", "description": "Move the pointer.",
     "inputSchema": schema({**XY, "display": DISPLAY_PROP}, ("x", "y"))},
    {"name": "drag", "description": "Drag with the left button from (x1,y1) to (x2,y2).",
     "inputSchema": schema({"x1": {"type": "integer"}, "y1": {"type": "integer"}, "x2": {"type": "integer"}, "y2": {"type": "integer"}, "display": DISPLAY_PROP}, ("x1", "y1", "x2", "y2"))},
    {"name": "scroll", "description": "Scroll at (x,y). direction: up|down|left|right.",
     "inputSchema": schema({**XY, "direction": {"type": "string", "enum": ["up", "down", "left", "right"]}, "amount": {"type": "integer", "default": 3}, "display": DISPLAY_PROP}, ("x", "y", "direction"))},
    {"name": "type", "description": "Type text with the keyboard.",
     "inputSchema": schema({"text": {"type": "string"}, "display": DISPLAY_PROP}, ("text",))},
    {"name": "key", "description": "Press a key combo in xdotool syntax, e.g. 'ctrl+l', 'Return', 'alt+F4'.",
     "inputSchema": schema({"keys": {"type": "string"}, "display": DISPLAY_PROP}, ("keys",))},
    {"name": "run", "description": "Run a bash command in the Linux guest; returns exit code, stdout and stderr.",
     "inputSchema": schema({"command": {"type": "string"}, "timeout": {"type": "integer", "default": 120}, "cwd": {"type": "string", "default": "/root"}, "display": DISPLAY_PROP}, ("command",))},
    {"name": "read_file", "description": "Read a UTF-8 text file.",
     "inputSchema": schema({"path": {"type": "string"}}, ("path",))},
    {"name": "write_file", "description": "Write a UTF-8 text file (parents are created).",
     "inputSchema": schema({"path": {"type": "string"}, "content": {"type": "string"}}, ("path", "content"))},
]


class ToolError(Exception):
    pass


def env_for(display):
    env = dict(os.environ)
    env["DISPLAY"] = display or ":1"
    return env


def xdotool(display, *args):
    proc = subprocess.run(["xdotool", *map(str, args)], env=env_for(display),
                          capture_output=True, text=True, timeout=30)
    if proc.returncode != 0:
        raise ToolError(f"xdotool {' '.join(map(str, args))}: {proc.stderr.strip()}")


def text(s):
    return {"content": [{"type": "text", "text": s}]}


def tool_list_displays(_a):
    out = []
    for sock in sorted(glob.glob("/tmp/.X11-unix/X*")):
        disp = ":" + sock.rsplit("X", 1)[1]
        p = subprocess.run(["xdpyinfo"], env=env_for(disp), capture_output=True, text=True, timeout=10)
        dims = next((l.split()[1] for l in p.stdout.splitlines() if "dimensions:" in l), "unknown")
        out.append({"display": disp, "size": dims})
    return text(json.dumps(out))


def tool_screenshot(a):
    display = a.get("display", ":1")
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "shot.png")
        p = subprocess.run(["scrot", "-o", "-p", path], env=env_for(display),
                           capture_output=True, text=True, timeout=30)
        if p.returncode != 0:
            raise ToolError(f"scrot: {p.stderr.strip()}")
        data = open(path, "rb").read()
    scale = 1.0
    max_width = a.get("max_width")
    if max_width:
        from PIL import Image
        img = Image.open(io.BytesIO(data))
        if img.width > max_width:
            scale = max_width / img.width
            img = img.resize((max_width, round(img.height * scale)), Image.LANCZOS)
            buf = io.BytesIO()
            img.save(buf, "PNG")
            data = buf.getvalue()
    return {"content": [
        {"type": "image", "data": base64.b64encode(data).decode(), "mimeType": "image/png"},
        {"type": "text", "text": json.dumps({"display": display, "scale": scale})},
    ]}


def tool_click(a):
    d = a.get("display", ":1")
    xdotool(d, "mousemove", a["x"], a["y"], "click", "--repeat", a.get("count", 1), a.get("button", 1))
    return text("ok")


def tool_move(a):
    xdotool(a.get("display", ":1"), "mousemove", a["x"], a["y"])
    return text("ok")


def tool_drag(a):
    d = a.get("display", ":1")
    xdotool(d, "mousemove", a["x1"], a["y1"], "mousedown", 1, "mousemove", a["x2"], a["y2"], "mouseup", 1)
    return text("ok")


def tool_scroll(a):
    button = {"up": 4, "down": 5, "left": 6, "right": 7}[a["direction"]]
    d = a.get("display", ":1")
    xdotool(d, "mousemove", a["x"], a["y"], "click", "--repeat", a.get("amount", 3), button)
    return text("ok")


def tool_type(a):
    xdotool(a.get("display", ":1"), "type", "--delay", 12, "--", a["text"])
    return text("ok")


def tool_key(a):
    xdotool(a.get("display", ":1"), "key", "--", *a["keys"].split())
    return text("ok")


def tool_run(a):
    try:
        p = subprocess.run(["bash", "-lc", a["command"]], cwd=a.get("cwd", "/root"),
                           env=env_for(a.get("display", ":1")), capture_output=True,
                           text=True, timeout=a.get("timeout", 120))
    except subprocess.TimeoutExpired:
        raise ToolError(f"timeout after {a.get('timeout', 120)}s")
    return text(json.dumps({"exit_code": p.returncode,
                            "stdout": p.stdout[-MAX_OUTPUT:], "stderr": p.stderr[-MAX_OUTPUT:]}))


def tool_read_file(a):
    with open(a["path"], encoding="utf-8", errors="replace") as f:
        return text(f.read(MAX_OUTPUT * 10))


def tool_write_file(a):
    os.makedirs(os.path.dirname(os.path.abspath(a["path"])), exist_ok=True)
    with open(a["path"], "w", encoding="utf-8") as f:
        f.write(a["content"])
    return text("ok")


HANDLERS = {t["name"]: globals()["tool_" + t["name"]] for t in TOOLS}


def handle_rpc(msg):
    """Returns a JSON-RPC response dict, or None for notifications."""
    method, mid = msg.get("method"), msg.get("id")
    if mid is None:
        return None
    try:
        if method == "initialize":
            requested = (msg.get("params") or {}).get("protocolVersion")
            version = requested if requested in PROTOCOL_VERSIONS else PROTOCOL_VERSIONS[0]
            result = {"protocolVersion": version, "capabilities": {"tools": {}},
                      "serverInfo": {"name": "linuxxr-agentd", "version": "0.1.0"}}
        elif method == "ping":
            result = {}
        elif method == "tools/list":
            result = {"tools": TOOLS}
        elif method == "tools/call":
            params = msg.get("params") or {}
            handler = HANDLERS.get(params.get("name"))
            if handler is None:
                return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32602, "message": f"unknown tool {params.get('name')}"}}
            try:
                result = handler(params.get("arguments") or {})
            except (ToolError, OSError, KeyError, subprocess.SubprocessError) as e:
                result = {"content": [{"type": "text", "text": f"{type(e).__name__}: {e}"}], "isError": True}
        else:
            return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"method not found: {method}"}}
    except Exception as e:  # never kill the server on a bad request
        return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32603, "message": str(e)}}
    return {"jsonrpc": "2.0", "id": mid, "result": result}


def load_token():
    with open(TOKEN_FILE) as f:
        token = f.read().strip()
    if len(token) < 16:
        sys.exit(f"agentd: token in {TOKEN_FILE} is missing or too short")
    return token


class Handler(BaseHTTPRequestHandler):
    token = ""
    server_version = "linuxxr-agentd"

    def _reply(self, code, body=None):
        data = b"" if body is None else json.dumps(body).encode()
        self.send_response(code)
        if body is not None:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self):
        origin = self.headers.get("Origin")
        # DNS-rebinding protection: browsers send Origin; only local origins may talk to us.
        if origin and not origin.startswith(("http://127.0.0.1", "http://localhost")):
            return False
        auth = self.headers.get("Authorization", "")
        return auth.startswith("Bearer ") and hmac.compare_digest(auth[7:].strip(), self.token)

    def do_GET(self):
        if self.path == "/health":
            return self._reply(200, {"ok": True})
        self._reply(405)

    def do_POST(self):
        if self.path.split("?")[0] != "/mcp":
            return self._reply(404)
        if not self._authorized():
            return self._reply(401, {"error": "unauthorized"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length))
        except (ValueError, json.JSONDecodeError):
            return self._reply(400, {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse error"}})
        if isinstance(payload, list):
            responses = [r for r in map(handle_rpc, payload) if r is not None]
            return self._reply(200, responses) if responses else self._reply(202)
        response = handle_rpc(payload)
        self._reply(202) if response is None else self._reply(200, response)

    def log_message(self, fmt, *args):
        sys.stderr.write("agentd: " + fmt % args + "\n")


def main():
    Handler.token = load_token()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"agentd listening on http://{HOST}:{PORT}/mcp", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
