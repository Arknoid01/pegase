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
