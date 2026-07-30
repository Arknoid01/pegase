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
