#!/usr/bin/env bash
# Copier vers /workspace/setup.sh sur le volume RunPod.
# Pégase lance : bash /workspace/setup.sh — le script NE DOIT PAS quitter
# (sinon le proxy RunPod affiche « Waiting for service to respond »).
#
# Preview live HTML : port 3000 (fileserver) — actif par défaut.
# Désactiver : ORION_FILESERVER=0
# Proxy Ollama agentique : port 11435 — actif par défaut.
# Désactiver : ORION_PROXY=0
set -u

WORK=/workspace
MODEL="${MODEL:-qwen3-coder:30b}"
CS_PORT="${CS_PORT:-8080}"
FILE_PORT="${FILE_PORT:-3000}"
ORION_FILESERVER="${ORION_FILESERVER:-1}"
# Proxy instrumentation Ollama (journal agentique) — actif par defaut.
# Desactiver : ORION_PROXY=0
ORION_PROXY="${ORION_PROXY:-1}"
OLLAMA_PROXY_PORT="${OLLAMA_PROXY_PORT:-11435}"
CS_PASSWORD="${CS_PASSWORD:-123456789}"
# Même valeur que Pégase → Réglages → Orion → Token
ORION_TOKEN="${ORION_TOKEN:-${OLLAMA_API_KEY:-123456789}}"

# Dossiers PERSISTANTS sur le volume
CS_DATA="$WORK/.code-server-data"
CS_EXT="$WORK/.code-server-extensions"

export OLLAMA_MODELS="${OLLAMA_MODELS:-$WORK/ollama-models}"
export OLLAMA_HOST="${OLLAMA_HOST:-0.0.0.0:11434}"
export OLLAMA_LOAD_TIMEOUT="${OLLAMA_LOAD_TIMEOUT:-20m}"
export OLLAMA_KEEP_ALIVE="${OLLAMA_KEEP_ALIVE:--1}"
# Contexte large : missions Orion (~14–18k chars + tools) saturent 4k/8k.
export OLLAMA_CONTEXT_LENGTH="${OLLAMA_CONTEXT_LENGTH:-32768}"

mkdir -p "$WORK" "$CS_DATA" "$CS_EXT" "$OLLAMA_MODELS" "$WORK/projects" || true
cd "$WORK" || true

# --- 0. Prérequis système (réinstallés à chaque pod neuf) ---
echo ">> Verification des prerequis (curl, zstd, nano, git)..."
ensure_prereqs() {
  local need=0
  command -v curl >/dev/null 2>&1 || need=1
  command -v zstd >/dev/null 2>&1 || need=1
  command -v nano >/dev/null 2>&1 || need=1
  command -v git >/dev/null 2>&1 || need=1
  command -v rg >/dev/null 2>&1 || need=1
  if [ "$need" = "0" ]; then
    return 0
  fi
  if command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq || true
    apt-get install -y -qq curl zstd nano git ripgrep || true
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y curl zstd nano git || true
  elif command -v yum >/dev/null 2>&1; then
    yum install -y curl zstd nano git || true
  fi
}
ensure_prereqs

# Node/npm — requis pour eslint (lint Orion). Sans ça, /lint → tool_missing.
# Préférer un Node récent : ESLint 9 exige Node 18+ (le nodejs apt Ubuntu 22 = trop vieux).
ensure_nodejs() {
  if command -v node >/dev/null 2>&1; then
    # Majeur >= 18 ?
    local major
    major=$(node -p "process.versions.node.split('.')[0]" 2>/dev/null || echo 0)
    if [ "${major:-0}" -ge 18 ] 2>/dev/null && command -v npm >/dev/null 2>&1; then
      echo ">> Node/npm OK : $(node -v) / $(npm -v)"
      return 0
    fi
    echo ">> Node trop vieux ($(node -v 2>/dev/null)) — install binaire 20.x…"
  else
    echo ">> Installation Node.js 20 (binaire officiel)…"
  fi
  local ver="v20.18.1"
  local arch="linux-x64"
  local url="https://nodejs.org/dist/${ver}/node-${ver}-${arch}.tar.xz"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$url" -o /tmp/node20.tar.xz \
      && tar -xJf /tmp/node20.tar.xz -C /usr/local --strip-components=1 \
      && rm -f /tmp/node20.tar.xz \
      || echo ">> WARN : telechargement Node echoue"
  fi
  hash -r 2>/dev/null || true
  export PATH="/usr/local/bin:${PATH:-}"
  if command -v npm >/dev/null 2>&1; then
    major=$(node -p "process.versions.node.split('.')[0]" 2>/dev/null || echo 0)
    if [ "${major:-0}" -ge 18 ] 2>/dev/null; then
      echo ">> Node/npm OK : $(node -v) / $(npm -v)"
      return 0
    fi
  fi
  echo ">> WARN : npm/Node 18+ absent — lint web desactive (tool_missing)"
  echo "npm/node18 absent apres ensure_nodejs ($(date -u +%Y-%m-%dT%H:%M:%SZ))" \
    >> "$WORK/lint-install.log" 2>/dev/null || true
  return 1
}
ensure_nodejs || true

# --- 0b. Preview live (port 3000) — tôt pour que le proxy RunPod réponde ---
start_fileserver() {
  [ "${ORION_FILESERVER}" = "1" ] || return 0
  command -v python3 >/dev/null 2>&1 || return 0
  # Lancement ComfyUI partagé (boot + POST /start-comfy)
  cat > "$WORK/comfy_boot.py" << 'COMFYBOOTEOF' || true
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
  # Graphe fichiers niveaux 1+2 (importe par fileserver.py)
  cat > "$WORK/project_graph.py" << 'GRAPHEOF' || true
#!/usr/bin/env python3
"""
Graphe de fichiers Orion — niveaux 1 + 2 + 3.

Niveau 1 — couplage explicite :
  HTML : <script src>, <link href>
  JS   : import … from, require(…)
  CSS  : @import, url(…)

Niveau 2 — identifiants partagés :
  HTML définit id / class
  JS utilise getElementById, querySelector, classList…
  CSS définit / référence #id et .class
  → related élargi si intersection defines∪uses

Niveau 3 — symboles JS (ast-grep ou regex fallback) :
  function $NAME / const $NAME = () => / appels $NAME()
  tokens sym:name dans defines/uses
  GET /symbols?project=&name=

Stockage : /workspace/projects/<projet>/.graph.json
Jamais d'exception remontée aux callers — toujours un dict ok.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
from typing import Any, Dict, List, Optional, Set, Tuple

GRAPH_NAME = ".graph.json"
WEB_EXT = (".html", ".htm", ".js", ".mjs", ".css")
GRAPH_LEVEL = 3
# IDF-like : un identifiant présent dans trop de fichiers ne crée plus de lien L2
IDF_MAX_FILES_SMALL = 3          # projets ≤ 12 fichiers
IDF_MAX_RATIO = 0.25             # projets plus grands : plafond = max(3, ceil(n * ratio))
IDF_SMALL_PROJECT = 12
# Classes / ids trop génériques même à faible DF (bruit CSS courant)
GENERIC_IDENTS = frozenset({
    "class:container", "class:wrapper", "class:content", "class:main",
    "class:header", "class:footer", "class:nav", "class:btn", "class:button",
    "class:row", "class:col", "class:grid", "class:flex", "class:item",
    "class:title", "class:text", "class:active", "class:hidden", "class:show",
    "class:primary", "class:secondary", "class:icon", "class:logo",
    "id:root", "id:app", "id:main", "id:content", "id:header", "id:footer",
})

GENERIC_SYMBOLS = frozenset({
    "sym:console", "sym:log", "sym:document", "sym:window", "sym:globalThis",
    "sym:setTimeout", "sym:setInterval", "sym:clearTimeout", "sym:clearInterval",
    "sym:parseInt", "sym:parseFloat", "sym:isNaN", "sym:encodeURIComponent",
    "sym:JSON", "sym:Math", "sym:Date", "sym:Array", "sym:Object", "sym:String",
    "sym:Number", "sym:Boolean", "sym:Promise", "sym:Map", "sym:Set", "sym:Error",
    "sym:getElementById", "sym:querySelector", "sym:querySelectorAll",
    "sym:addEventListener", "sym:removeEventListener", "sym:createElement",
    "sym:fetch", "sym:alert", "sym:confirm", "sym:require", "sym:define",
    "sym:module", "sym:exports", "sym:process", "sym:Buffer",
})
_JS_CALL_SKIP = frozenset({
    "if", "for", "while", "switch", "catch", "function", "return", "typeof",
    "instanceof", "new", "await", "async", "class", "const", "let", "var",
    "throw", "yield", "import", "export", "from", "of", "in", "else", "do",
    "try", "finally", "with", "debugger", "void", "delete", "super", "this",
})
_RE_FN_DECL = re.compile(r"\bfunction\s+([A-Za-z_][\w]*)\s*\(")
_RE_ARROW_FN = re.compile(
    r"\b(?:const|let|var)\s+([A-Za-z_][\w]*)\s*=\s*(?:async\s*)?"
    r"(?:\([^)]*\)|[A-Za-z_][\w]*)\s*=>"
)
_RE_FN_CALL = re.compile(r"\b([A-Za-z_][\w]*)\s*\(")



_RE_SCRIPT = re.compile(
    r"""<script[^>]+src\s*=\s*["']([^"']+)["']""",
    re.IGNORECASE,
)
_RE_LINK = re.compile(
    r"""<link[^>]+href\s*=\s*["']([^"']+)["']""",
    re.IGNORECASE,
)
_RE_IMPORT_FROM = re.compile(
    r"""(?:import|export)\s+(?:[^'"\n]+?\s+from\s+)?["']([^"']+)["']""",
    re.MULTILINE,
)
_RE_REQUIRE = re.compile(r"""require\s*\(\s*["']([^"']+)["']\s*\)""")
_RE_CSS_IMPORT = re.compile(
    r"""@import\s+(?:url\s*\(\s*)?["']?([^"')\s;]+)["']?\s*\)?""",
    re.IGNORECASE,
)
_RE_CSS_URL = re.compile(
    r"""url\s*\(\s*["']?([^"')]+)["']?\s*\)""",
    re.IGNORECASE,
)

_RE_HTML_ID = re.compile(r"""\bid\s*=\s*["']([^"']+)["']""", re.IGNORECASE)
_RE_HTML_CLASS = re.compile(r"""\bclass\s*=\s*["']([^"']+)["']""", re.IGNORECASE)
_RE_JS_BY_ID = re.compile(
    r"""getElementById\s*\(\s*['"]([^'"]+)['"]\s*\)"""
)
_RE_JS_QS = re.compile(
    r"""querySelector(?:All)?\s*\(\s*['"]([^'"]+)['"]\s*\)"""
)
_RE_JS_CLASSLIST = re.compile(
    r"""classList\.(?:add|remove|toggle|contains)\s*\(\s*['"]([^'"]+)['"]"""
)
_RE_CSS_ID = re.compile(r"""#([A-Za-z_][\w-]*)""")
_RE_CSS_CLASS = re.compile(r"""\.([A-Za-z_][\w-]*)""")
# ignore pseudo / common false positives in CSS class extract
_CSS_SKIP = {
    "css", "important", "root", "host", "slotted", "deep", "global",
}


def _empty_graph() -> Dict[str, Any]:
    return {"version": 1, "level": GRAPH_LEVEL, "files": {}}


def _ok(extra: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    out: Dict[str, Any] = {"ok": True}
    if extra:
        out.update(extra)
    return out


def _norm_ref(raw: str) -> Optional[str]:
    if not raw:
        return None
    s = raw.strip().split("?", 1)[0].split("#", 1)[0].strip()
    if not s:
        return None
    low = s.lower()
    if low.startswith(("http://", "https://", "data:", "javascript:", "//")):
        return None
    s = s.replace("\\", "/")
    while s.startswith("./"):
        s = s[2:]
    if s.startswith("../") or "/../" in s or s.startswith("/"):
        s = s.rsplit("/", 1)[-1]
    if not s or ".." in s:
        return None
    return s


def _node() -> Dict[str, Any]:
    return {
        "defines": [],
        "uses": [],
        "imports": [],
        "importedBy": [],
    }


def _id_token(name: str) -> str:
    return "id:" + name.strip()


def _class_token(name: str) -> str:
    return "class:" + name.strip()


def _uniq(seq: List[str]) -> List[str]:
    out: List[str] = []
    seen: Set[str] = set()
    for x in seq:
        if not x or x in seen:
            continue
        seen.add(x)
        out.append(x)
    return out


def extract_imports(filename: str, content: str) -> List[str]:
    """Niveau 1 : références fichiers explicites."""
    if content is None:
        content = ""
    name = (filename or "").replace("\\", "/").rsplit("/", 1)[-1]
    low = name.lower()
    found: List[str] = []
    seen: Set[str] = set()

    def add(raw: str) -> None:
        n = _norm_ref(raw)
        if not n or n == name:
            return
        key = n.lower()
        if key in seen:
            return
        seen.add(key)
        found.append(n)

    try:
        if low.endswith((".html", ".htm")):
            for m in _RE_SCRIPT.finditer(content):
                add(m.group(1))
            for m in _RE_LINK.finditer(content):
                add(m.group(1))
        elif low.endswith((".js", ".mjs")):
            for m in _RE_IMPORT_FROM.finditer(content):
                add(m.group(1))
            for m in _RE_REQUIRE.finditer(content):
                add(m.group(1))
        elif low.endswith(".css"):
            for m in _RE_CSS_IMPORT.finditer(content):
                add(m.group(1))
            for m in _RE_CSS_URL.finditer(content):
                ref = m.group(1)
                n = _norm_ref(ref)
                if n and n.lower().endswith((".css", ".js", ".html", ".htm")):
                    add(ref)
    except Exception:
        return []
    return found


def _parse_selector_tokens(selector: str) -> Tuple[List[str], List[str]]:
    """Extrait #id et .class d'un sélecteur CSS / querySelector."""
    ids: List[str] = []
    classes: List[str] = []
    if not selector:
        return ids, classes
    for m in _RE_CSS_ID.finditer(selector):
        ids.append(m.group(1))
    for m in _RE_CSS_CLASS.finditer(selector):
        c = m.group(1)
        if c.lower() not in _CSS_SKIP:
            classes.append(c)
    return ids, classes


def extract_defines_uses(filename: str, content: str) -> Tuple[List[str], List[str]]:
    """
    Niveau 2 : identifiants.
    HTML → defines (id/class attrs)
    JS   → uses (DOM / classList)
    CSS  → defines (#id .class dans sélecteurs)
    """
    if content is None:
        content = ""
    name = (filename or "").replace("\\", "/").rsplit("/", 1)[-1]
    low = name.lower()
    defines: List[str] = []
    uses: List[str] = []
    try:
        if low.endswith((".html", ".htm")):
            for m in _RE_HTML_ID.finditer(content):
                t = m.group(1).strip()
                if t:
                    defines.append(_id_token(t))
            for m in _RE_HTML_CLASS.finditer(content):
                for part in m.group(1).split():
                    if part.strip():
                        defines.append(_class_token(part.strip()))
        elif low.endswith((".js", ".mjs")):
            for m in _RE_JS_BY_ID.finditer(content):
                uses.append(_id_token(m.group(1)))
            for m in _RE_JS_QS.finditer(content):
                ids, classes = _parse_selector_tokens(m.group(1))
                for i in ids:
                    uses.append(_id_token(i))
                for c in classes:
                    uses.append(_class_token(c))
            for m in _RE_JS_CLASSLIST.finditer(content):
                uses.append(_class_token(m.group(1)))
        elif low.endswith(".css"):
            ids, classes = _parse_selector_tokens(content)
            for i in ids:
                defines.append(_id_token(i))
            for c in classes:
                defines.append(_class_token(c))
    except Exception:
        return [], []
    return _uniq(defines), _uniq(uses)


def _rg_files_mentioning(proj_dir: str, needle: str, exclude: str) -> List[str]:
    """Bonus ripgrep : fichiers contenant la chaîne littérale (id/class nu)."""
    exe = shutil.which("rg")
    if not exe or not needle or len(needle) < 2:
        return []
    try:
        r = subprocess.run(
            [
                exe, "-l", "-F", "--glob", "*.{html,htm,js,mjs,css}",
                "--glob", "!.graph.json", needle, proj_dir,
            ],
            capture_output=True,
            text=True,
            timeout=3,
        )
    except Exception:
        return []
    out: List[str] = []
    for line in (r.stdout or "").splitlines():
        line = line.strip()
        if not line:
            continue
        base = os.path.basename(line)
        if base and base != exclude:
            out.append(base)
    return out



def _sym_token(name: str) -> str:
    return "sym:" + name.strip()


def _ast_grep_bin() -> Optional[str]:
    return shutil.which("ast-grep") or shutil.which("sg")


def _sg_metavars_name(obj: Dict[str, Any]) -> Optional[str]:
    try:
        mv = obj.get("metaVariables") or {}
        single = mv.get("single") or {}
        name = single.get("NAME") or single.get("name")
        if isinstance(name, dict):
            t = name.get("text")
            if isinstance(t, str) and t.strip():
                return t.strip()
        if isinstance(name, str) and name.strip():
            return name.strip()
    except Exception:
        pass
    return None


def _sg_run_names(pattern: str, path: str, lang: str = "javascript") -> List[str]:
    """ast-grep run -p pattern --json=stream → noms capturés $NAME."""
    exe = _ast_grep_bin()
    if not exe or not path or not os.path.isfile(path):
        return []
    try:
        r = subprocess.run(
            [exe, "run", "-p", pattern, "-l", lang, "--json=stream", path],
            capture_output=True,
            text=True,
            timeout=8,
        )
    except Exception:
        return []
    out: List[str] = []
    for line in (r.stdout or "").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except Exception:
            continue
        if not isinstance(obj, dict):
            continue
        n = _sg_metavars_name(obj)
        if n:
            out.append(n)
    return out


def extract_symbols(filename: str, content: str,
                    file_path: Optional[str] = None) -> Tuple[List[str], List[str]]:
    """
    Niveau 3 : symboles JS.
    Prefer ast-grep si dispo ; sinon regex.
    Retourne (defines, uses) en tokens sym:name.
    """
    name = (filename or "").replace("\\", "/").rsplit("/", 1)[-1]
    low = name.lower()
    if not low.endswith((".js", ".mjs")):
        return [], []
    if content is None:
        content = ""
    defines: List[str] = []
    uses: List[str] = []
    path = file_path if file_path and os.path.isfile(file_path) else None

    if path and _ast_grep_bin():
        for pat in (
            "function $NAME($$$)",
            "async function $NAME($$$)",
            "const $NAME = ($$$) => $$$",
            "let $NAME = ($$$) => $$$",
            "const $NAME = async ($$$) => $$$",
            "export function $NAME($$$)",
        ):
            for n in _sg_run_names(pat, path):
                if n and n not in _JS_CALL_SKIP:
                    defines.append(_sym_token(n))
        for n in _sg_run_names("$NAME($$$)", path):
            if n and n not in _JS_CALL_SKIP:
                uses.append(_sym_token(n))
    else:
        for m in _RE_FN_DECL.finditer(content):
            defines.append(_sym_token(m.group(1)))
        for m in _RE_ARROW_FN.finditer(content):
            defines.append(_sym_token(m.group(1)))
        for m in _RE_FN_CALL.finditer(content):
            n = m.group(1)
            if n in _JS_CALL_SKIP:
                continue
            uses.append(_sym_token(n))

    return _uniq(defines), _uniq(uses)


def _fill_node(file_name: str, content: str,
               file_path: Optional[str] = None) -> Dict[str, Any]:
    node = _node()
    node["imports"] = extract_imports(file_name, content)
    d2, u2 = extract_defines_uses(file_name, content)
    d3, u3 = extract_symbols(file_name, content, file_path=file_path)
    node["defines"] = _uniq(list(d2) + list(d3))
    node["uses"] = _uniq(list(u2) + list(u3))
    return node


def _idents_of(meta: Dict[str, Any]) -> Set[str]:
    out: Set[str] = set()
    for k in ("defines", "uses"):
        for x in meta.get(k) or []:
            if isinstance(x, str) and x:
                out.add(x)
    return out


def _ident_doc_freq(files: Dict[str, Any]) -> Dict[str, int]:
    """Nombre de fichiers où chaque identifiant apparaît (defines ∪ uses)."""
    df: Dict[str, int] = {}
    for meta in files.values():
        if not isinstance(meta, dict):
            continue
        for tok in _idents_of(meta):
            df[tok] = df.get(tok, 0) + 1
    return df


def _idf_threshold(n_files: int) -> int:
    """Plafond de document-frequency pour qu'un token reste discriminant."""
    n = max(0, int(n_files))
    if n <= IDF_SMALL_PROJECT:
        return IDF_MAX_FILES_SMALL
    # gros projet : 25 % des fichiers, min 3
    return max(IDF_MAX_FILES_SMALL, int((n * IDF_MAX_RATIO) + 0.999))


def _discriminative_idents(idents: Set[str], df: Dict[str, int],
                           threshold: int) -> Set[str]:
    """Écarte génériques + tokens trop répandus (style IDF)."""
    out: Set[str] = set()
    for tok in idents:
        if not tok or tok in GENERIC_IDENTS or tok in GENERIC_SYMBOLS:
            continue
        # class:btn déjà dans GENERIC ; aussi filtrer préfixes très courts
        raw = tok.split(":", 1)[-1] if ":" in tok else tok
        if len(raw) < 2:
            continue
        if tok.startswith("sym:") and len(raw) < 3:
            continue
        if df.get(tok, 0) > threshold:
            continue
        out.add(tok)
    return out


def _project_dir(root: str, project: str) -> Optional[str]:
    project = (project or "").strip().lstrip("/")
    if not project or ".." in project:
        return None
    projects = os.path.realpath(os.path.join(root, "projects"))
    target = os.path.realpath(os.path.join(projects, project))
    if not target.startswith(projects + os.sep) and target != projects:
        return None
    return target


def _graph_path(proj_dir: str) -> str:
    return os.path.join(proj_dir, GRAPH_NAME)


def load_graph(proj_dir: str) -> Dict[str, Any]:
    path = _graph_path(proj_dir)
    try:
        if os.path.isfile(path):
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, dict) and isinstance(data.get("files"), dict):
                return data
    except Exception:
        pass
    return _empty_graph()


def save_graph(proj_dir: str, graph: Dict[str, Any]) -> None:
    try:
        os.makedirs(proj_dir, exist_ok=True)
        path = _graph_path(proj_dir)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(graph, f, ensure_ascii=False, indent=2)
            f.write("\n")
        os.replace(tmp, path)
    except Exception:
        pass


def _rebuild_imported_by(files: Dict[str, Any]) -> None:
    for meta in files.values():
        if isinstance(meta, dict):
            meta["importedBy"] = []
    for src, meta in files.items():
        if not isinstance(meta, dict):
            continue
        for dep in meta.get("imports") or []:
            if not isinstance(dep, str):
                continue
            node = files.get(dep)
            if node is None:
                files[dep] = _node()
                node = files[dep]
            ib = node.setdefault("importedBy", [])
            if src not in ib:
                ib.append(src)


def _list_web_files(proj_dir: str) -> List[str]:
    out: List[str] = []
    try:
        for name in os.listdir(proj_dir):
            if name.startswith("."):
                continue
            low = name.lower()
            if not low.endswith(WEB_EXT):
                continue
            path = os.path.join(proj_dir, name)
            if os.path.isfile(path):
                out.append(name)
    except Exception:
        pass
    return sorted(out)


def reindex_file(root: str, project: str, file_name: str) -> Dict[str, Any]:
    """Reparse un fichier et met à jour .graph.json. Toujours ok."""
    try:
        file_name = (file_name or "").strip().lstrip("/").replace("\\", "/")
        if "/" in file_name:
            file_name = file_name.rsplit("/", 1)[-1]
        if not file_name or ".." in file_name:
            return _ok({"file": file_name or "", "reindexed": False})
        proj_dir = _project_dir(root, project)
        if not proj_dir or not os.path.isdir(proj_dir):
            return _ok({"file": file_name, "reindexed": False, "project_missing": True})
        path = os.path.realpath(os.path.join(proj_dir, file_name))
        if not path.startswith(os.path.realpath(proj_dir) + os.sep):
            return _ok({"file": file_name, "reindexed": False})
        graph = load_graph(proj_dir)
        files = graph.setdefault("files", {})
        if not os.path.isfile(path):
            if file_name in files:
                del files[file_name]
                _rebuild_imported_by(files)
                save_graph(proj_dir, graph)
            return _ok({"file": file_name, "reindexed": True, "removed": True})
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                content = f.read()
        except Exception:
            return _ok({"file": file_name, "reindexed": False})
        node = _fill_node(file_name, content, file_path=path)
        files[file_name] = node
        _rebuild_imported_by(files)
        graph["level"] = GRAPH_LEVEL
        graph["version"] = 1
        save_graph(proj_dir, graph)
        return _ok({
            "file": file_name,
            "reindexed": True,
            "imports": node["imports"],
            "defines": node["defines"],
            "uses": node["uses"],
            "level": GRAPH_LEVEL,
        })
    except Exception:
        return _ok({"file": file_name or "", "reindexed": False})


def reindex_project(root: str, project: str) -> Dict[str, Any]:
    """Indexe tous les fichiers web du projet."""
    try:
        proj_dir = _project_dir(root, project)
        if not proj_dir or not os.path.isdir(proj_dir):
            return _ok({"project": project or "", "files": 0, "project_missing": True})
        names = _list_web_files(proj_dir)
        graph = _empty_graph()
        files = graph["files"]
        for name in names:
            path = os.path.join(proj_dir, name)
            try:
                with open(path, "r", encoding="utf-8", errors="replace") as f:
                    content = f.read()
            except Exception:
                content = ""
            files[name] = _fill_node(name, content, file_path=path)
        _rebuild_imported_by(files)
        save_graph(proj_dir, graph)
        return _ok({
            "project": project,
            "files": len(files),
            "reindexed": True,
            "level": GRAPH_LEVEL,
        })
    except Exception:
        return _ok({"project": project or "", "files": 0, "reindexed": False})


def get_graph(root: str, project: str) -> Dict[str, Any]:
    try:
        proj_dir = _project_dir(root, project)
        if not proj_dir or not os.path.isdir(proj_dir):
            return _ok({"project": project or "", "files": {}, "project_missing": True})
        graph = load_graph(proj_dir)
        # Reindex si vide ou graphe encore niveau 1 sans defines
        need = not graph.get("files")
        if not need and int(graph.get("level") or 1) < GRAPH_LEVEL:
            need = True
        if need:
            reindex_project(root, project)
            graph = load_graph(proj_dir)
        return _ok({
            "project": project,
            "version": graph.get("version", 1),
            "level": graph.get("level", GRAPH_LEVEL),
            "files": graph.get("files") or {},
        })
    except Exception:
        return _ok({"project": project or "", "files": {}})



def get_symbols(root: str, project: str, name: str) -> Dict[str, Any]:
    """Défini dans… / référencé dans… pour un symbole JS."""
    try:
        raw = (name or "").strip()
        if raw.startswith("sym:"):
            raw = raw[4:]
        if not raw or ".." in raw:
            return _ok({"name": name or "", "defined_in": [], "referenced_in": []})
        tok = _sym_token(raw)
        g = get_graph(root, project)
        files = g.get("files") or {}
        defined_in: List[str] = []
        referenced_in: List[str] = []
        for fname, meta in files.items():
            if not isinstance(meta, dict):
                continue
            defs = meta.get("defines") or []
            uses = meta.get("uses") or []
            if tok in defs:
                defined_in.append(fname)
            if tok in uses:
                referenced_in.append(fname)
        return _ok({
            "name": raw,
            "token": tok,
            "defined_in": defined_in,
            "referenced_in": referenced_in,
            "level": GRAPH_LEVEL,
        })
    except Exception:
        return _ok({"name": name or "", "defined_in": [], "referenced_in": []})


def get_related(root: str, project: str, file_name: str) -> Dict[str, Any]:
    """Fichiers liés : L1 + L2 (ids) + L3 (symboles JS discriminants)."""
    try:
        file_name = (file_name or "").strip().lstrip("/").replace("\\", "/")
        if "/" in file_name:
            file_name = file_name.rsplit("/", 1)[-1]
        g = get_graph(root, project)
        files = g.get("files") or {}
        related: List[str] = []
        seen: Set[str] = set()

        def add(n: str) -> None:
            if not n or n.lower() in seen:
                return
            seen.add(n.lower())
            related.append(n)

        if file_name:
            if file_name not in files:
                reindex_file(root, project, file_name)
                proj_dir = _project_dir(root, project)
                if proj_dir:
                    files = load_graph(proj_dir).get("files") or files

            add(file_name)
            meta = files.get(file_name) or {}
            # L1
            for dep in meta.get("imports") or []:
                if isinstance(dep, str):
                    add(dep)
            for src in meta.get("importedBy") or []:
                if isinstance(src, str):
                    add(src)
            # L2 — intersection d'identifiants discriminants (anti-.container)
            mine_raw = _idents_of(meta if isinstance(meta, dict) else {})
            df = _ident_doc_freq(files)
            thr = _idf_threshold(len(files))
            mine = _discriminative_idents(mine_raw, df, thr)
            if mine:
                for other_name, other_meta in files.items():
                    if not isinstance(other_meta, dict):
                        continue
                    if other_name.lower() == file_name.lower():
                        continue
                    other = _discriminative_idents(_idents_of(other_meta), df, thr)
                    if mine & other:
                        add(other_name)
            # Bonus rg : uniquement sur tokens discriminants (ids surtout)
            proj_dir = _project_dir(root, project)
            if proj_dir and mine:
                for tok in list(mine)[:40]:
                    if not tok.startswith("id:"):
                        continue
                    raw = tok.split(":", 1)[1]
                    for hit in _rg_files_mentioning(proj_dir, raw, file_name):
                        add(hit)
        return _ok({
            "project": project or "",
            "file": file_name or "",
            "related": related,
            "level": GRAPH_LEVEL,
            "idf_threshold": _idf_threshold(len(files)) if files else IDF_MAX_FILES_SMALL,
        })
    except Exception:
        return _ok({
            "project": project or "",
            "file": file_name or "",
            "related": [file_name] if file_name else [],
            "level": GRAPH_LEVEL,
        })
GRAPHEOF

  cat > "$WORK/fileserver.py" << 'EOF' || return 0
from http.server import HTTPServer, SimpleHTTPRequestHandler
from urllib.parse import urlparse, parse_qs, unquote
import json
import os
import shutil
import subprocess
import traceback

ROOT = os.environ.get("ORION_FILE_ROOT", "/workspace")
TOKEN = os.environ.get("ORION_TOKEN", os.environ.get("OLLAMA_API_KEY", ""))
NPM_BIN = os.environ.get("NPM_GLOBAL", os.path.join(ROOT, ".npm-global"))
PATH_EXTRA = os.path.join(NPM_BIN, "bin")
# /usr/local/bin : Node binaire (eslint shebang → env node)
os.environ["PATH"] = (
    PATH_EXTRA + os.pathsep
    + "/usr/local/bin" + os.pathsep
    + os.environ.get("PATH", "")
)
# Résolution node_modules locaux (paquet globals, etc.)
_nm = os.path.join(ROOT, "node_modules")
_prev_np = os.environ.get("NODE_PATH", "")
os.environ["NODE_PATH"] = _nm + (os.pathsep + _prev_np if _prev_np else "")
os.chdir(ROOT)

ESLINT_CONFIG = os.path.join(ROOT, "eslint.config.mjs")

def _json_response(handler, code, obj):
    body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    handler.send_response(code)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)

def _empty_ok(file_name, tool, tool_missing=False):
    out = {"file": file_name or "", "tool": tool or "", "ok": True, "issues": []}
    if tool_missing:
        out["tool_missing"] = True
    return out

def _run(cmd, cwd, timeout=5):
    return subprocess.run(
        cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout,
        env=os.environ.copy())

def _sev(v):
    if v is None:
        return "warning"
    if isinstance(v, int):
        return "error" if v >= 2 else "warning"
    s = str(v).lower()
    if s in ("error", "2", "fatal"):
        return "error"
    return "warning"

def _lint_eslint(path, cwd, file_name):
    exe = shutil.which("eslint")
    if not exe:
        return _empty_ok(file_name, "eslint", tool_missing=True)
    try:
        # cwd=ROOT + --config explicite → toujours /workspace/eslint.config.mjs
        cmd = [exe, "--format", "json", "--no-error-on-unmatched-pattern"]
        if os.path.isfile(ESLINT_CONFIG):
            cmd.extend(["--config", ESLINT_CONFIG])
        cmd.append(path)
        r = _run(cmd, ROOT)
    except subprocess.TimeoutExpired:
        return _empty_ok(file_name, "eslint", tool_missing=True)
    except Exception:
        return _empty_ok(file_name, "eslint", tool_missing=True)
    issues = []
    try:
        data = json.loads(r.stdout or "[]")
        if isinstance(data, list):
            for block in data:
                for m in block.get("messages") or []:
                    issues.append({
                        "line": int(m.get("line") or 0),
                        "column": int(m.get("column") or 0),
                        "severity": _sev(m.get("severity")),
                        "rule": m.get("ruleId") or "",
                        "message": m.get("message") or "",
                    })
    except Exception:
        pass
    return {"file": file_name, "tool": "eslint", "ok": len(issues) == 0, "issues": issues}

def _lint_html(path, cwd, file_name):
    exe = shutil.which("html-validate")
    if not exe:
        return _empty_ok(file_name, "html-validate", tool_missing=True)
    try:
        r = _run([exe, "--formatter", "json", path], cwd)
    except subprocess.TimeoutExpired:
        return _empty_ok(file_name, "html-validate", tool_missing=True)
    except Exception:
        return _empty_ok(file_name, "html-validate", tool_missing=True)
    issues = []
    try:
        data = json.loads(r.stdout or "[]")
        results = data if isinstance(data, list) else data.get("results") or []
        for block in results:
            for m in block.get("messages") or []:
                issues.append({
                    "line": int(m.get("line") or 0),
                    "column": int(m.get("column") or 0),
                    "severity": _sev(m.get("severity")),
                    "rule": m.get("ruleId") or m.get("rule") or "",
                    "message": m.get("message") or "",
                })
    except Exception:
        pass
    return {"file": file_name, "tool": "html-validate", "ok": len(issues) == 0, "issues": issues}

def _lint_style(path, cwd, file_name):
    exe = shutil.which("stylelint")
    if not exe:
        return _empty_ok(file_name, "stylelint", tool_missing=True)
    try:
        r = _run([exe, "--formatter", "json", "--allow-empty-input", path], cwd)
    except subprocess.TimeoutExpired:
        return _empty_ok(file_name, "stylelint", tool_missing=True)
    except Exception:
        return _empty_ok(file_name, "stylelint", tool_missing=True)
    issues = []
    try:
        data = json.loads(r.stdout or "[]")
        if isinstance(data, list):
            for block in data:
                for m in block.get("warnings") or []:
                    issues.append({
                        "line": int(m.get("line") or 0),
                        "column": int(m.get("column") or 0),
                        "severity": _sev(m.get("severity")),
                        "rule": m.get("rule") or "",
                        "message": m.get("text") or m.get("message") or "",
                    })
    except Exception:
        pass
    return {"file": file_name, "tool": "stylelint", "ok": len(issues) == 0, "issues": issues}

def lint_file(project, file_name):
    file_name = (file_name or "").strip().lstrip("/")
    project = (project or "").strip().lstrip("/")
    if not file_name or ".." in file_name or ".." in project:
        return _empty_ok(file_name, "", tool_missing=True)
    projects = os.path.realpath(os.path.join(ROOT, "projects"))
    if project:
        target = os.path.realpath(os.path.join(projects, project, file_name))
    else:
        target = os.path.realpath(os.path.join(projects, file_name))
    if not target.startswith(projects + os.sep):
        return _empty_ok(file_name, "", tool_missing=True)
    if not os.path.isfile(target):
        return _empty_ok(file_name, "", tool_missing=True)
    cwd = ROOT
    low = file_name.lower()
    try:
        if low.endswith(".js") or low.endswith(".mjs"):
            return _lint_eslint(target, cwd, file_name)
        if low.endswith(".html") or low.endswith(".htm"):
            return _lint_html(target, cwd, file_name)
        if low.endswith(".css"):
            return _lint_style(target, cwd, file_name)
    except Exception:
        traceback.print_exc()
        return _empty_ok(file_name, "", tool_missing=True)
    return _empty_ok(file_name, "")

try:
    import project_graph as _pg
except Exception:
    _pg = None

def _graph_call(fn, *args):
    try:
        if _pg is None:
            return {"ok": True}
        return fn(*args) or {"ok": True}
    except Exception:
        return {"ok": True}

try:
    from comfy_boot import start_comfy
except Exception:
    def start_comfy():
        return None, "comfy_boot.py manquant"

class Handler(SimpleHTTPRequestHandler):
    def _auth_ok(self):
        if not TOKEN:
            return True
        return self.headers.get("Authorization", "") == "Bearer " + TOKEN

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        if path == "/lint":
            if not self._auth_ok():
                _json_response(self, 401, {"error": "unauthorized"})
                return
            qs = parse_qs(parsed.query)
            file_name = unquote((qs.get("file") or [""])[0])
            project = unquote((qs.get("project") or [""])[0])
            try:
                result = lint_file(project, file_name)
            except Exception:
                result = _empty_ok(file_name, "", tool_missing=True)
            _json_response(self, 200, result)
            return
        if path == "/graph":
            if not self._auth_ok():
                _json_response(self, 401, {"error": "unauthorized"})
                return
            qs = parse_qs(parsed.query)
            project = unquote((qs.get("project") or [""])[0])
            result = _graph_call(_pg.get_graph, ROOT, project) if _pg else {"ok": True, "files": {}}
            _json_response(self, 200, result)
            return
        if path == "/related":
            if not self._auth_ok():
                _json_response(self, 401, {"error": "unauthorized"})
                return
            qs = parse_qs(parsed.query)
            project = unquote((qs.get("project") or [""])[0])
            file_name = unquote((qs.get("file") or [""])[0])
            result = _graph_call(_pg.get_related, ROOT, project, file_name) if _pg else {"ok": True, "related": []}
            _json_response(self, 200, result)
            return
        if path == "/symbols":
            if not self._auth_ok():
                _json_response(self, 401, {"error": "unauthorized"})
                return
            qs = parse_qs(parsed.query)
            project = unquote((qs.get("project") or [""])[0])
            name = unquote((qs.get("name") or [""])[0])
            result = _graph_call(_pg.get_symbols, ROOT, project, name) if _pg else {
                "ok": True, "name": name, "defined_in": [], "referenced_in": []
            }
            _json_response(self, 200, result)
            return
        return SimpleHTTPRequestHandler.do_GET(self)

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        # Consommer le body sans l'interpréter (jamais de commande client)
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
        if path == "/reindex":
            if not self._auth_ok():
                _json_response(self, 401, {"error": "unauthorized"})
                return
            qs = parse_qs(parsed.query)
            project = unquote((qs.get("project") or [""])[0])
            file_name = unquote((qs.get("file") or [""])[0])
            if file_name:
                result = _graph_call(_pg.reindex_file, ROOT, project, file_name) if _pg else {"ok": True}
            else:
                result = _graph_call(_pg.reindex_project, ROOT, project) if _pg else {"ok": True}
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
        # Reindex graphe L1–L3 best-effort (jamais bloquant)
        try:
            if _pg is not None and real.startswith(projects + os.sep):
                rel = os.path.relpath(real, projects).replace("\\", "/")
                parts = rel.split("/")
                if len(parts) >= 2:
                    _pg.reindex_file(ROOT, parts[0], parts[-1])
        except Exception:
            pass
        self.send_response(200); self.end_headers(); self.wfile.write(b"OK")
    def log_message(self, *args):
        pass
HTTPServer(("0.0.0.0", int(os.environ.get("FILE_PORT", "3000"))), Handler).serve_forever()
EOF
  export ORION_TOKEN FILE_PORT ORION_FILE_ROOT="$WORK"
  export NPM_GLOBAL="${NPM_GLOBAL:-$WORK/.npm-global}"
  export PATH="${NPM_GLOBAL}/bin:${PATH:-}"
  pkill -f "fileserver.py" 2>/dev/null || true
  nohup env PATH="$PATH" NPM_GLOBAL="$NPM_GLOBAL" ORION_TOKEN="$ORION_TOKEN" \
    FILE_PORT="$FILE_PORT" ORION_FILE_ROOT="$WORK" \
    python3 "$WORK/fileserver.py" > "$WORK/fileserver.log" 2>&1 &
  echo ">> fileserver preview :$FILE_PORT"
}
start_fileserver || true

# --- 1. Ollama ---
ensure_ollama() {
  if command -v ollama >/dev/null 2>&1; then
    return 0
  fi
  if ! command -v zstd >/dev/null 2>&1; then
    echo ">> ERREUR : zstd manquant — Ollama install.sh ne peut pas extraire."
    ensure_prereqs
  fi
  if ! command -v zstd >/dev/null 2>&1; then
    echo ">> Impossible d'installer Ollama sans zstd."
    return 1
  fi
  echo ">> Installation d'Ollama..."
  curl -fsSL https://ollama.com/install.sh | sh || true
  command -v ollama >/dev/null 2>&1
}
ensure_ollama || true

echo ">> Demarrage du serveur Ollama..."
echo ">> OLLAMA_MODELS=$OLLAMA_MODELS (modeles sur le volume)"
echo ">> OLLAMA_CONTEXT_LENGTH=$OLLAMA_CONTEXT_LENGTH"
echo ">> Token Orion : ${ORION_TOKEN:0:4}… (${#ORION_TOKEN} chars) — doit matcher Pegase"
pkill -f "ollama serve" 2>/dev/null || true
sleep 2
if command -v ollama >/dev/null 2>&1; then
  nohup env \
    OLLAMA_MODELS="$OLLAMA_MODELS" \
    OLLAMA_LOAD_TIMEOUT="$OLLAMA_LOAD_TIMEOUT" \
    OLLAMA_KEEP_ALIVE="$OLLAMA_KEEP_ALIVE" \
    OLLAMA_HOST="$OLLAMA_HOST" \
    OLLAMA_CONTEXT_LENGTH="$OLLAMA_CONTEXT_LENGTH" \
    OLLAMA_API_KEY="$ORION_TOKEN" \
    ollama serve > "$WORK/ollama.log" 2>&1 &
  sleep 5
else
  echo ">> Ollama absent — voir logs" >> "$WORK/ollama.log"
fi

echo ">> Attente Ollama..."
OLLAMA_READY=0
for i in $(seq 1 24); do
  if curl -sf -H "Authorization: Bearer $ORION_TOKEN" \
       http://127.0.0.1:11434/api/tags >/dev/null 2>&1 \
     || curl -sf http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
    echo ">> Ollama pret !"
    OLLAMA_READY=1
    break
  fi
  sleep 5
done
if [ "$OLLAMA_READY" != "1" ]; then
  echo "!! Ollama ne repond pas — voir $WORK/ollama.log"
  tail -n 40 "$WORK/ollama.log" || true
fi


# --- 1b. Proxy Ollama (journal /workspace/orion-calls.jsonl) ---
start_ollama_proxy() {
  [ "${ORION_PROXY}" = "1" ] || return 0
  command -v python3 >/dev/null 2>&1 || return 0
  cat > "$WORK/ollamaproxy.py" << 'PROXYEOF'
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

PROXYEOF
  export OLLAMA_UPSTREAM="http://127.0.0.1:11434"
  export OLLAMA_PROXY_HOST="0.0.0.0"
  export OLLAMA_PROXY_PORT="${OLLAMA_PROXY_PORT:-11435}"
  export ORION_CALLS_LOG="$WORK/orion-calls.jsonl"
  pkill -f "ollamaproxy.py" 2>/dev/null || true
  sleep 1
  nohup env \
    OLLAMA_UPSTREAM="$OLLAMA_UPSTREAM" \
    OLLAMA_PROXY_HOST="$OLLAMA_PROXY_HOST" \
    OLLAMA_PROXY_PORT="$OLLAMA_PROXY_PORT" \
    ORION_CALLS_LOG="$ORION_CALLS_LOG" \
    python3 "$WORK/ollamaproxy.py" > "$WORK/ollamaproxy.log" 2>&1 &
  echo ">> ollama proxy :$OLLAMA_PROXY_PORT -> 127.0.0.1:11434 ($WORK/orion-calls.jsonl)"
}
if [ "$OLLAMA_READY" = "1" ]; then
  start_ollama_proxy || true
fi

sleep 3
echo ">> Modeles detectes :"
ollama list || true

if ollama list 2>/dev/null | grep -qi "qwen3-coder"; then
  echo ">> Modele deja present sur le volume, on saute le telechargement."
else
  echo ">> Telechargement de $MODEL (~18 Go, une seule fois)..."
  ollama pull "$MODEL" || true
fi

# --- 2. code-server (éditeur VS Code / Cline) ---
echo ">> Verification code-server..."
if ! command -v code-server >/dev/null 2>&1; then
  echo ">> Installation code-server..."
  curl -fsSL https://code-server.dev/install.sh | sh || true
fi

mkdir -p "$HOME/.config/code-server" || true
cat > "$HOME/.config/code-server/config.yaml" << EOF
bind-addr: 0.0.0.0:${CS_PORT}
auth: password
password: ${CS_PASSWORD}
cert: false
EOF

pkill -f "code-server" 2>/dev/null || true
sleep 1
if command -v code-server >/dev/null 2>&1; then
  nohup code-server \
    --config "$HOME/.config/code-server/config.yaml" \
    --user-data-dir "$CS_DATA" \
    --extensions-dir "$CS_EXT" \
    "$WORK" >> "$WORK/code-server.log" 2>&1 &
  echo ">> code-server demarre (port $CS_PORT)"
else
  echo "!! code-server introuvable — VS Code web indisponible"
fi

# --- 2b. Linters web (bonus — jamais bloquant) ---
install_web_linters() {
  # Dernière chance si ensure_nodejs a échoué plus tôt
  if ! command -v npm >/dev/null 2>&1; then
    ensure_nodejs || true
  fi
  if ! command -v npm >/dev/null 2>&1; then
    echo ">> Lint : npm absent — skip (eslint / html-validate / stylelint)"
    {
      echo "SKIP $(date -u +%Y-%m-%dT%H:%M:%SZ) : npm absent — install_web_linters noop"
      echo "  which node=$(command -v node || echo NONE) npm=$(command -v npm || echo NONE)"
    } >> "$WORK/lint-install.log" 2>/dev/null || true
    return 0
  fi
  NPM_GLOBAL="${NPM_GLOBAL:-$WORK/.npm-global}"
  mkdir -p "$NPM_GLOBAL" || true
  export npm_config_prefix="$NPM_GLOBAL"
  export PATH="$NPM_GLOBAL/bin:$PATH"

  if command -v eslint >/dev/null 2>&1 && command -v stylelint >/dev/null 2>&1; then
    echo ">> Linters deja presents sur le volume."
  else
    echo ">> Installation linters web (prefix=$NPM_GLOBAL)…"
    npm install -g eslint html-validate stylelint stylelint-config-standard \
      @ast-grep/cli \
      >> "$WORK/lint-install.log" 2>&1 \
      || echo ">> Lint : npm install a echoue — voir $WORK/lint-install.log (pod OK quand meme)"
  fi

  # globals browser — liste complète maintenue (évite faux positifs no-undef).
  # On génère une config STATIQUE (pas d'import runtime) : sinon eslint
  # depuis /workspace ne résout pas toujours node_modules/globals.
  npm install --prefix "$WORK" globals@15 \
    >> "$WORK/lint-install.log" 2>&1 \
    || echo ">> Lint : npm install globals a echoue — fallback liste manuelle"

  # Flat config ESLint 9+ (écrasée à chaque setup)
  wrote_globals=0
  if [ -d "$WORK/node_modules/globals" ]; then
    if NODE_PATH="$WORK/node_modules${NODE_PATH:+:$NODE_PATH}" node << 'GEN' > "$WORK/eslint.config.mjs"
const globals = require("globals");
const cfg = [
  {
    files: ["**/*.js", "**/*.mjs"],
    languageOptions: {
      ecmaVersion: "latest",
      sourceType: "module",
      globals: { ...globals.browser }
    },
    rules: {
      "no-undef": "error",
      "no-unused-vars": "warn",
      "no-redeclare": "error",
      "no-dupe-keys": "error",
      "no-unreachable": "error",
      "no-const-assign": "error"
    }
  }
];
process.stdout.write(
  "// Generated from globals.browser — no runtime import\n"
  + "export default " + JSON.stringify(cfg, null, 2) + ";\n"
);
GEN
    then
      wrote_globals=1
      echo ">> Ecrit $WORK/eslint.config.mjs (globals.browser inline)"
    else
      echo ">> Lint : generation globals.browser a echoue — fallback manuel"
      rm -f "$WORK/eslint.config.mjs" 2>/dev/null || true
    fi
  fi

  if [ "$wrote_globals" != "1" ]; then
    cat > "$WORK/eslint.config.mjs" << 'ESLINT'
export default [
  {
    files: ["**/*.js", "**/*.mjs"],
    languageOptions: {
      ecmaVersion: "latest",
      sourceType: "module",
      globals: {
        window: "readonly", document: "readonly", console: "readonly",
        navigator: "readonly", location: "readonly", history: "readonly",
        localStorage: "readonly", sessionStorage: "readonly",
        fetch: "readonly", Headers: "readonly", Request: "readonly", Response: "readonly",
        URL: "readonly", URLSearchParams: "readonly", FormData: "readonly", Blob: "readonly",
        File: "readonly", FileReader: "readonly", AbortController: "readonly",
        setInterval: "readonly", clearInterval: "readonly",
        setTimeout: "readonly", clearTimeout: "readonly",
        requestAnimationFrame: "readonly", cancelAnimationFrame: "readonly",
        queueMicrotask: "readonly", Promise: "readonly",
        alert: "readonly", confirm: "readonly", prompt: "readonly",
        atob: "readonly", btoa: "readonly", structuredClone: "readonly",
        matchMedia: "readonly", getComputedStyle: "readonly",
        MutationObserver: "readonly", ResizeObserver: "readonly", IntersectionObserver: "readonly",
        CustomEvent: "readonly", Event: "readonly", EventTarget: "readonly",
        addEventListener: "readonly", removeEventListener: "readonly", dispatchEvent: "readonly",
        HTMLElement: "readonly", Element: "readonly", Node: "readonly",
        Image: "readonly", Audio: "readonly", Worker: "readonly",
        crypto: "readonly", performance: "readonly", Intl: "readonly",
        TextEncoder: "readonly", TextDecoder: "readonly",
        Map: "readonly", Set: "readonly", WeakMap: "readonly", WeakSet: "readonly",
        Proxy: "readonly", Reflect: "readonly", Symbol: "readonly",
        JSON: "readonly", Math: "readonly", Date: "readonly", Array: "readonly",
        Object: "readonly", String: "readonly", Number: "readonly", Boolean: "readonly",
        Error: "readonly", TypeError: "readonly", RangeError: "readonly",
        parseInt: "readonly", parseFloat: "readonly", isNaN: "readonly", isFinite: "readonly",
        encodeURIComponent: "readonly", decodeURIComponent: "readonly",
        CSS: "readonly", DOMParser: "readonly", XMLSerializer: "readonly"
      }
    },
    rules: {
      "no-undef": "error",
      "no-unused-vars": "warn",
      "no-redeclare": "error",
      "no-dupe-keys": "error",
      "no-unreachable": "error",
      "no-const-assign": "error"
    }
  }
];
ESLINT
    echo ">> Ecrit $WORK/eslint.config.mjs (liste manuelle elargie)"
  fi

  # ast-grep (symboles L3) — best-effort
  if ! command -v ast-grep >/dev/null 2>&1 && ! command -v sg >/dev/null 2>&1; then
    echo ">> Installation @ast-grep/cli…"
    npm install -g @ast-grep/cli >> "$WORK/lint-install.log" 2>&1 \
      || echo ">> ast-grep : install echoue — regex fallback graphe L3"
  else
    echo ">> ast-grep deja present."
  fi

  if [ ! -f "$WORK/.htmlvalidate.json" ]; then
    cat > "$WORK/.htmlvalidate.json" << 'HTMLV'
{
  "extends": ["html-validate:recommended"]
}
HTMLV
    echo ">> Ecrit $WORK/.htmlvalidate.json"
  fi
  if [ ! -f "$WORK/.stylelintrc.json" ]; then
    cat > "$WORK/.stylelintrc.json" << 'STYLE'
{
  "extends": "stylelint-config-standard"
}
STYLE
    echo ">> Ecrit $WORK/.stylelintrc.json"
  fi
}
install_web_linters || true
# Relancer le fileserver pour exposer /lint avec les binaires npm
start_fileserver || true

echo ""
echo "======================================================"
echo "  PRET."
echo ""
echo "  EDITEUR (code-server) :"
echo "    https://<ID-DU-POD>-${CS_PORT}.proxy.runpod.net"
echo "    Mot de passe : $CS_PASSWORD"
echo ""
echo "  ORION (Pegase → Ollama) :"
echo "    https://<ID-DU-POD>-11434.proxy.runpod.net"
echo "    Token : $ORION_TOKEN"
if [ "${ORION_PROXY}" = "1" ]; then
  echo ""
  echo "  PROXY AGENTIQUE (Pegase -> :$OLLAMA_PROXY_PORT -> Ollama) :"
  echo "    https://<ID-DU-POD>-${OLLAMA_PROXY_PORT}.proxy.runpod.net"
  echo "    Journal : $WORK/orion-calls.jsonl"
  echo "    (pointe Pegase sur le port proxy pour instrumenter)"
fi
echo ""
echo "  PREVIEW LIVE (WebView) :"
echo "    https://<ID-DU-POD>-${FILE_PORT}.proxy.runpod.net/projects/"
echo "    Graphe L1+L2+L3 : /graph /related /symbols /reindex  →  projects/<p>/.graph.json"
echo ""
echo "  CLINE (dans code-server) :"
echo "    API Provider : Ollama"
echo "    Base URL     : http://localhost:11434"
echo "    Model        : $MODEL"
echo ""
echo "  Logs : $WORK/ollama.log  |  $WORK/code-server.log  |  $WORK/fileserver.log  |  $WORK/ollamaproxy.log"
echo "======================================================"

# CRITIQUE : ne pas quitter
echo ">> Conteneur maintenu actif (Ollama + code-server + preview)…"
while true; do
  if command -v ollama >/dev/null 2>&1; then
    if ! pgrep -f "ollama serve" >/dev/null 2>&1; then
      echo "!! ollama serve arrete — redemarrage…"
      nohup env \
        OLLAMA_MODELS="$OLLAMA_MODELS" \
        OLLAMA_LOAD_TIMEOUT="$OLLAMA_LOAD_TIMEOUT" \
        OLLAMA_KEEP_ALIVE="$OLLAMA_KEEP_ALIVE" \
        OLLAMA_HOST="$OLLAMA_HOST" \
        OLLAMA_CONTEXT_LENGTH="${OLLAMA_CONTEXT_LENGTH:-32768}" \
        OLLAMA_API_KEY="$ORION_TOKEN" \
        ollama serve >> "$WORK/ollama.log" 2>&1 &
    fi
  else
    ensure_ollama >> "$WORK/ollama.log" 2>&1 || true
  fi
  if command -v code-server >/dev/null 2>&1; then
    if ! pgrep -f "code-server" >/dev/null 2>&1; then
      echo "!! code-server arrete — redemarrage…"
      nohup code-server \
        --config "$HOME/.config/code-server/config.yaml" \
        --user-data-dir "$CS_DATA" \
        --extensions-dir "$CS_EXT" \
        "$WORK" >> "$WORK/code-server.log" 2>&1 &
    fi
  fi
  if [ "${ORION_FILESERVER}" = "1" ]; then
    if ! pgrep -f "fileserver.py" >/dev/null 2>&1; then
      start_fileserver || true
    fi
  fi
  if [ "${ORION_PROXY}" = "1" ]; then
    if ! pgrep -f "ollamaproxy.py" >/dev/null 2>&1; then
      echo "!! ollamaproxy arrete — redemarrage…"
      start_ollama_proxy || true
    fi
  fi
  sleep 30
done
