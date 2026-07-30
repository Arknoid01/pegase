#!/usr/bin/env python3
"""
Proxy Ollama (instrumentation agentique Orion / Pégase).

Écoute 0.0.0.0:11435 → relaie vers http://127.0.0.1:11434
Journal : /workspace/orion-calls.jsonl (chat/generate uniquement).
Ne loggue jamais le texte des prompts ni le contenu des fichiers.
Toute erreur de journalisation est avalée — le relais passe toujours.
"""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import json
import os
import time
import urllib.error
import urllib.request

UPSTREAM = os.environ.get("OLLAMA_UPSTREAM", "http://127.0.0.1:11434").rstrip("/")
LISTEN = os.environ.get("OLLAMA_PROXY_HOST", "0.0.0.0")
PORT = int(os.environ.get("OLLAMA_PROXY_PORT", "11435"))
LOG_PATH = os.environ.get("ORION_CALLS_LOG", "/workspace/orion-calls.jsonl")

HOP_BY_HOP = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailers",
    "transfer-encoding",
    "upgrade",
    "host",
    "content-length",
}


def _path_only(path):
    return (path or "/").split("?", 1)[0]


def _should_log(path):
    p = _path_only(path)
    return p == "/api/chat" or p == "/api/generate" or p.endswith("/api/chat") or p.endswith(
        "/api/generate"
    )


def _prompt_chars(body):
    if not body:
        return 0
    try:
        data = json.loads(body.decode("utf-8"))
    except Exception:
        return len(body)
    n = 0
    try:
        prompt = data.get("prompt")
        if isinstance(prompt, str):
            n += len(prompt)
        msgs = data.get("messages")
        if isinstance(msgs, list):
            for m in msgs:
                if not isinstance(m, dict):
                    continue
                c = m.get("content")
                if isinstance(c, str):
                    n += len(c)
                elif isinstance(c, list):
                    # multimodal : compter uniquement les blocs texte
                    for part in c:
                        if isinstance(part, dict) and isinstance(part.get("text"), str):
                            n += len(part["text"])
                        elif isinstance(part, str):
                            n += len(part)
    except Exception:
        return len(body)
    return n


def _parse_args(args):
    if isinstance(args, dict):
        return args
    if isinstance(args, str) and args.strip():
        try:
            obj = json.loads(args)
            return obj if isinstance(obj, dict) else {}
        except Exception:
            return {}
    return {}


def _filename_from_args(args):
    args = _parse_args(args)
    for key in ("filename", "path", "file"):
        v = args.get(key)
        if isinstance(v, str) and v.strip():
            return v.strip()
        if v is not None and not isinstance(v, (dict, list)):
            return str(v)
    return ""


def _tool_calls_from_list(raw):
    out = []
    if not isinstance(raw, list):
        return out
    for tc in raw:
        if not isinstance(tc, dict):
            continue
        name = ""
        args = {}
        fn = tc.get("function")
        if isinstance(fn, dict):
            name = fn.get("name") or ""
            args = fn.get("arguments")
        else:
            name = tc.get("name") or ""
            args = tc.get("arguments")
        out.append({
            "name": name if isinstance(name, str) and name else "unknown",
            "filename": _filename_from_args(args),
        })
    return out


def _tool_calls_from_obj(data):
    out = []
    if not isinstance(data, dict):
        return out
    msg = data.get("message")
    if isinstance(msg, dict):
        out.extend(_tool_calls_from_list(msg.get("tool_calls")))
    out.extend(_tool_calls_from_list(data.get("tool_calls")))
    return out


def extract_tool_calls(body):
    """Extrait name + filename uniquement. Échec → []."""
    try:
        if not body:
            return []
        text = body.decode("utf-8", errors="replace")
        try:
            return _tool_calls_from_obj(json.loads(text))
        except Exception:
            pass
        out = []
        seen = set()
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                for tc in _tool_calls_from_obj(json.loads(line)):
                    key = (tc.get("name"), tc.get("filename"))
                    if key in seen:
                        continue
                    seen.add(key)
                    out.append(tc)
            except Exception:
                continue
        return out
    except Exception:
        return []


def append_log(entry):
    try:
        with open(LOG_PATH, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    except Exception:
        pass


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        pass

    def do_GET(self):
        self._proxy()

    def do_POST(self):
        self._proxy()

    def do_PUT(self):
        self._proxy()

    def do_DELETE(self):
        self._proxy()

    def do_HEAD(self):
        self._proxy()

    def do_OPTIONS(self):
        self._proxy()

    def do_PATCH(self):
        self._proxy()

    def _proxy(self):
        t0 = time.time()
        t_ms = int(t0 * 1000)
        path = self.path or "/"
        path_base = _path_only(path)
        should_log = _should_log(path)

        try:
            length = int(self.headers.get("Content-Length", 0) or 0)
        except Exception:
            length = 0
        try:
            body = self.rfile.read(length) if length > 0 else b""
        except Exception:
            body = b""

        prompt_chars = 0
        if should_log:
            try:
                prompt_chars = _prompt_chars(body)
            except Exception:
                prompt_chars = 0

        headers = {}
        try:
            for k, v in self.headers.items():
                if k.lower() in HOP_BY_HOP:
                    continue
                headers[k] = v
        except Exception:
            headers = {}

        url = UPSTREAM + path
        req = urllib.request.Request(
            url,
            data=body if body else None,
            headers=headers,
            method=self.command,
        )

        buf = bytearray()
        status = 502
        try:
            with urllib.request.urlopen(req, timeout=3600) as resp:
                status = getattr(resp, "status", None) or resp.getcode() or 200
                self.send_response(int(status))
                try:
                    for k, v in resp.headers.items():
                        if k.lower() in HOP_BY_HOP:
                            continue
                        self.send_header(k, v)
                except Exception:
                    pass
                # Relais streaming : pas de Content-Length figé — EOF = fin
                self.send_header("Connection", "close")
                self.end_headers()
                if self.command != "HEAD":
                    while True:
                        chunk = resp.read(65536)
                        if not chunk:
                            break
                        buf.extend(chunk)
                        try:
                            self.wfile.write(chunk)
                            self.wfile.flush()
                        except Exception:
                            break
        except Exception as exc:
            try:
                err = json.dumps({"error": "ollama_proxy_upstream", "detail": str(exc)}).encode(
                    "utf-8"
                )
                self.send_response(502)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(err)))
                self.send_header("Connection", "close")
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(err)
            except Exception:
                pass

        if should_log:
            try:
                try:
                    response_chars = len(bytes(buf).decode("utf-8", errors="replace"))
                except Exception:
                    response_chars = len(buf)
                try:
                    tool_calls = extract_tool_calls(bytes(buf))
                except Exception:
                    tool_calls = []
                append_log({
                    "t": t_ms,
                    "path": path_base,
                    "prompt_chars": int(prompt_chars),
                    "response_chars": int(response_chars),
                    "duration_ms": int((time.time() - t0) * 1000),
                    "tool_calls": tool_calls,
                })
            except Exception:
                pass


def main():
    server = ThreadingHTTPServer((LISTEN, PORT), Handler)
    server.daemon_threads = True
    server.serve_forever()


if __name__ == "__main__":
    main()
