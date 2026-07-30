#!/usr/bin/env bash
# Copier vers /workspace/setup-comfy.sh sur le volume RunPod.
# Pégase lance : bash /workspace/setup-comfy.sh — NE DOIT PAS quitter.
# Boot ComfyUI (pas Ollama). /start-comfy = filet de relance (même logique).

set -u
WORK="${WORK:-/workspace}"
FILE_PORT="${FILE_PORT:-3000}"
ORION_FILESERVER=1
ORION_TOKEN="${ORION_TOKEN:-${OLLAMA_API_KEY:-}}"
export WORK FILE_PORT ORION_FILESERVER ORION_TOKEN
export ORION_FILE_ROOT="$WORK"
mkdir -p "$WORK/projects"

echo ">> setup-comfy.sh — ComfyUI + fileserver (sans Ollama)"

# --- comfy_boot.py (source unique avec fileserver /start-comfy) ---
cat > "$WORK/comfy_boot.py" << 'COMFYBOOTEOF'
#!/usr/bin/env python3
"""Lancement ComfyUI idempotent — importé par fileserver (/start-comfy) et par le boot."""
from __future__ import annotations

import os
import subprocess

ROOT = os.environ.get("ORION_FILE_ROOT", "/workspace")

# Patterns pgrep : chemins absolus — évite le faux positif où bash -c
# contient la chaîne "main.py --listen" dans sa propre ligne de commande.
COMFY_MAIN = "/workspace/ComfyUI/main.py"
LIST_OUTPUTS = "/workspace/list_outputs.py"


def _pgrep(pattern: str) -> bool:
    try:
        r = subprocess.run(
            ["pgrep", "-f", pattern],
            capture_output=True, text=True, timeout=3)
        return r.returncode == 0
    except Exception:
        return False


def _start_comfy_detached() -> None:
    """Deps + main.py + list_outputs — détaché. Pas de set -e (pip peut hoqueter)."""
    # Chemins absolus ; marqueur dans /tmp (pip = conteneur, pas le volume).
    script = (
        "WORK=/workspace\n"
        "COMFY=\"$WORK/ComfyUI\"\n"
        "MARKER=\"/tmp/.comfy_deps_ok\"\n"
        "MAIN=\"$COMFY/main.py\"\n"
        "LIST=\"$WORK/list_outputs.py\"\n"
        "PY=python3\n"
        "command -v python3 >/dev/null 2>&1 || PY=python\n"
        "if [ ! -d \"$COMFY\" ] || [ ! -f \"$MAIN\" ]; then\n"
        "  echo \"ERREUR: $MAIN absent\" >&2\n"
        "  exit 1\n"
        "fi\n"
        "if [ ! -f \"$MARKER\" ]; then\n"
        "  echo \">> pip install requirements…\"\n"
        "  cd \"$COMFY\"\n"
        "  if $PY -m pip install -r requirements.txt \\\n"
        "     && $PY -m pip install sqlalchemy aiohttp alembic tqdm psutil pyyaml \\\n"
        "          pillow requests einops safetensors kornia spandrel soundfile \\\n"
        "          torchsde transformers tokenizers comfyui-frontend-package av \\\n"
        "          pydantic \\\n"
        "     && { $PY -m pip install \"numpy>=2,<2.8\" \"scipy>=1.13\" \\\n"
        "          || $PY -m pip install \"numpy<2\" \"scipy<1.13\"; }; then\n"
        "    touch \"$MARKER\"\n"
        "    echo \">> deps OK ($MARKER)\"\n"
        "  else\n"
        "    echo \"deps KO — on tente ComfyUI quand même\" >&2\n"
        "  fi\n"
        "fi\n"
        "if ! pgrep -f \"$MAIN\" >/dev/null 2>&1; then\n"
        "  echo \">> start ComfyUI : $MAIN\"\n"
        "  cd \"$COMFY\" && nohup $PY \"$MAIN\" --listen 0.0.0.0 --port 8188 "
        "--enable-cors-header --disable-all-custom-nodes "
        "> \"$WORK/comfyui.log\" 2>&1 &\n"
        "  echo \">> ComfyUI pid $!\"\n"
        "else\n"
        "  echo \">> ComfyUI déjà lancé\"\n"
        "fi\n"
        "if [ -f \"$LIST\" ] && ! pgrep -f \"$LIST\" >/dev/null 2>&1; then\n"
        "  echo \">> start list_outputs\"\n"
        "  nohup $PY \"$LIST\" > \"$WORK/list_outputs.log\" 2>&1 &\n"
        "elif [ ! -f \"$LIST\" ]; then\n"
        "  echo \">> list_outputs.py absent — ignore\"\n"
        "fi\n"
    )
    log_path = os.path.join(ROOT, "start-comfy.log")
    logf = open(log_path, "a")
    subprocess.Popen(
        ["bash", "-c", script],
        stdout=logf,
        stderr=subprocess.STDOUT,
        start_new_session=True,
        cwd=ROOT,
    )


def start_comfy():
    """Aucune commande client. Retourne (payload_dict, error_str)."""
    comfy_dir = os.path.join(ROOT, "ComfyUI")
    main_py = os.path.join(comfy_dir, "main.py")
    if not os.path.isdir(comfy_dir) or not os.path.isfile(main_py):
        return None, "ComfyUI absent (/workspace/ComfyUI/main.py)"
    comfy_already = _pgrep(COMFY_MAIN)
    list_already = _pgrep(LIST_OUTPUTS)
    if not comfy_already or (
            os.path.isfile(os.path.join(ROOT, "list_outputs.py")) and not list_already):
        _start_comfy_detached()
    return {
        "ok": True,
        "comfy": "already" if comfy_already else "running",
        "list": "already" if list_already else "running",
    }, None


if __name__ == "__main__":
    result, err = start_comfy()
    if err:
        print("ERROR:", err)
        raise SystemExit(1)
    print(result)
COMFYBOOTEOF

# --- fileserver minimal (auth + /start-comfy + preview projects) ---
cat > "$WORK/fileserver.py" << 'EOF'
from http.server import HTTPServer, SimpleHTTPRequestHandler
from urllib.parse import urlparse
import json
import os

ROOT = os.environ.get("ORION_FILE_ROOT", "/workspace")
TOKEN = os.environ.get("ORION_TOKEN", os.environ.get("OLLAMA_API_KEY", ""))
os.chdir(ROOT)

try:
    from comfy_boot import start_comfy
except Exception:
    def start_comfy():
        return None, "comfy_boot.py manquant"

def _json_response(handler, code, obj):
    body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    handler.send_response(code)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)

class Handler(SimpleHTTPRequestHandler):
    def _auth_ok(self):
        if not TOKEN:
            return True
        return self.headers.get("Authorization", "") == "Bearer " + TOKEN

    def do_GET(self):
        return SimpleHTTPRequestHandler.do_GET(self)

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        length = int(self.headers.get("Content-Length", 0) or 0)
        if length > 0:
            self.rfile.read(min(length, 1_048_576))
        if path == "/start-comfy":
            if not self._auth_ok():
                _json_response(self, 401, {"error": "unauthorized"})
                return
            try:
                result, err = start_comfy()
            except Exception as e:
                _json_response(self, 500, {"ok": False, "error": str(e)})
                return
            if err:
                _json_response(self, 500, {"ok": False, "error": err})
                return
            _json_response(self, 200, result)
            return
        self.send_response(404)
        self.end_headers()

    def do_PUT(self):
        if not self._auth_ok():
            self.send_response(401); self.end_headers(); self.wfile.write(b"unauthorized"); return
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length > 0 else b""
        path = self.translate_path(self.path)
        projects = os.path.realpath(os.path.join(ROOT, "projects"))
        real = os.path.realpath(path)
        if not real.startswith(projects + os.sep) and real != projects:
            self.send_response(403); self.end_headers(); self.wfile.write(b"forbidden"); return
        os.makedirs(os.path.dirname(real), exist_ok=True)
        with open(real, "wb") as f:
            f.write(body)
        self.send_response(200); self.end_headers(); self.wfile.write(b"OK")

    def log_message(self, *args):
        pass

HTTPServer(("0.0.0.0", int(os.environ.get("FILE_PORT", "3000"))), Handler).serve_forever()
EOF

pkill -f "fileserver.py" 2>/dev/null || true
nohup env PATH="${PATH:-}" ORION_TOKEN="$ORION_TOKEN" FILE_PORT="$FILE_PORT" \
  ORION_FILE_ROOT="$WORK" \
  python3 "$WORK/fileserver.py" > "$WORK/fileserver.log" 2>&1 &
echo ">> fileserver preview :$FILE_PORT"

# Boot : même start_comfy() que POST /start-comfy (pas d'attente Pégase)
sleep 1
echo ">> Boot ComfyUI via comfy_boot.start_comfy()…"
cd "$WORK"
python3 -c "from comfy_boot import start_comfy; r,e=start_comfy(); print(r if r else e)" \
  >> "$WORK/start-comfy.log" 2>&1 || echo "!! start_comfy boot a échoué — voir start-comfy.log"

echo ""
echo "======================================================"
echo "  COMFY PRET."
echo "  Preview : https://<POD>-${FILE_PORT}.proxy.runpod.net/"
echo "  ComfyUI : https://<POD>-8188.proxy.runpod.net/"
echo "  Relance : POST /start-comfy (Bearer ORION_TOKEN)"
echo "  Logs    : $WORK/comfyui.log | $WORK/fileserver.log | $WORK/start-comfy.log"
echo "======================================================"

echo ">> Conteneur maintenu actif (fileserver + ComfyUI)…"
while true; do
  if ! pgrep -f "fileserver.py" >/dev/null 2>&1; then
    echo "!! fileserver arrete — redemarrage…"
    nohup env PATH="${PATH:-}" ORION_TOKEN="$ORION_TOKEN" FILE_PORT="$FILE_PORT" \
      ORION_FILE_ROOT="$WORK" \
      python3 "$WORK/fileserver.py" > "$WORK/fileserver.log" 2>&1 &
  fi
  if [ -d "$WORK/ComfyUI" ] && ! pgrep -f "/workspace/ComfyUI/main.py" >/dev/null 2>&1; then
    echo "!! ComfyUI arrete — relance via comfy_boot…"
    python3 -c "from comfy_boot import start_comfy; start_comfy()" \
      >> "$WORK/start-comfy.log" 2>&1 || true
  fi
  sleep 30
done
