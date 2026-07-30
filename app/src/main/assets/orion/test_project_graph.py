"""Tests locaux du graphe Orion niveaux 1 + 2 + 3."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import project_graph as pg


def test_html_scripts_and_links():
    html = """
    <html><head>
      <link rel="stylesheet" href="style.css">
      <script src="https://cdn.example/x.js"></script>
      <script src="./app.js"></script>
    </head></html>
    """
    deps = pg.extract_imports("index.html", html)
    assert "style.css" in deps
    assert "app.js" in deps
    assert not any("cdn" in d for d in deps)


def test_js_import_require():
    js = """
    import { foo } from './lib.js';
    const x = require("util.js");
    export { bar } from "bar.js";
    """
    deps = pg.extract_imports("main.js", js)
    assert "lib.js" in deps
    assert "util.js" in deps
    assert "bar.js" in deps


def test_css_import():
    css = """
    @import "theme.css";
    @import url(other.css);
    body { background: url(bg.png); }
    """
    deps = pg.extract_imports("style.css", css)
    assert "theme.css" in deps
    assert "other.css" in deps
    assert "bg.png" not in deps


def test_level2_html_defines():
    html = '<button id="pausebtn" class="btn primary">Pause</button>'
    defines, uses = pg.extract_defines_uses("index.html", html)
    assert "id:pausebtn" in defines
    assert "class:btn" in defines
    assert "class:primary" in defines
    assert uses == []


def test_level2_js_uses():
    js = """
    const b = document.getElementById('pausebtn');
    document.querySelector('.btn');
    document.querySelector('#timer');
    el.classList.add('active');
    """
    defines, uses = pg.extract_defines_uses("timer.js", js)
    assert defines == []
    assert "id:pausebtn" in uses
    assert "class:btn" in uses
    assert "id:timer" in uses
    assert "class:active" in uses


def test_level2_css_defines():
    css = "#pausebtn { color: red; } .btn { display: block; }"
    defines, uses = pg.extract_defines_uses("style.css", css)
    assert "id:pausebtn" in defines
    assert "class:btn" in defines


def test_related_roundtrip(tmp_path):
    root = tmp_path
    proj = root / "projects" / "demo"
    proj.mkdir(parents=True)
    (proj / "index.html").write_text(
        '<link href="a.css"><script src="a.js"></script>', encoding="utf-8")
    (proj / "a.js").write_text('import "./b.js";', encoding="utf-8")
    (proj / "a.css").write_text("body{}", encoding="utf-8")
    (proj / "b.js").write_text("//", encoding="utf-8")
    r = pg.reindex_project(str(root), "demo")
    assert r["ok"] and r["files"] >= 3
    rel = pg.get_related(str(root), "demo", "index.html")
    assert "index.html" in rel["related"]
    assert "a.css" in rel["related"]
    assert "a.js" in rel["related"]
    g = pg.get_graph(str(root), "demo")
    assert "a.js" in g["files"]
    assert "b.js" in g["files"]["a.js"]["imports"]
    assert "a.js" in g["files"]["b.js"]["importedBy"]


def test_related_level2_id_link(tmp_path):
    """HTML id=pausebtn + JS getElementById — pas d'import, liés en L2."""
    root = tmp_path
    proj = root / "projects" / "sport"
    proj.mkdir(parents=True)
    (proj / "timer.html").write_text(
        '<button id="pausebtn">Pause</button>\n<script src="timer.js"></script>\n',
        encoding="utf-8",
    )
    (proj / "timer.js").write_text(
        "document.getElementById('pausebtn').onclick = () => {};\n",
        encoding="utf-8",
    )
    (proj / "other.js").write_text("console.log('noop');\n", encoding="utf-8")
    pg.reindex_project(str(root), "sport")
    # Depuis JS seul (sans import HTML) → doit trouver timer.html via id
    rel = pg.get_related(str(root), "sport", "timer.js")
    assert "timer.js" in rel["related"]
    assert "timer.html" in rel["related"]
    assert "other.js" not in rel["related"]
    assert rel["level"] == 3
    g = pg.get_graph(str(root), "sport")
    assert "id:pausebtn" in g["files"]["timer.html"]["defines"]
    assert "id:pausebtn" in g["files"]["timer.js"]["uses"]


def test_idf_filters_generic_container(tmp_path):
    """class:container partout → pas de lien ; id:pausebtn reste discriminant."""
    root = tmp_path
    proj = root / "projects" / "web"
    proj.mkdir(parents=True)
    for i, name in enumerate(["a.html", "b.html", "c.html", "d.html"]):
        (proj / name).write_text(
            f'<div class="container" id="{"pausebtn" if i == 0 else "x" + str(i)}">x</div>\n',
            encoding="utf-8",
        )
    (proj / "a.js").write_text(
        "document.querySelector('.container');\n"
        "document.getElementById('pausebtn');\n",
        encoding="utf-8",
    )
    (proj / "noise.js").write_text(
        "document.querySelector('.container');\n",
        encoding="utf-8",
    )
    pg.reindex_project(str(root), "web")
    rel = pg.get_related(str(root), "web", "a.js")
    # lié à a.html via pausebtn, PAS à b/c/d/noise via .container
    assert "a.html" in rel["related"]
    assert "b.html" not in rel["related"]
    assert "c.html" not in rel["related"]
    assert "noise.js" not in rel["related"]


def test_discriminative_helpers():
    df = {"class:container": 5, "id:pausebtn": 2, "class:btn": 2}
    thr = pg._idf_threshold(8)
    assert thr == 3
    got = pg._discriminative_idents(
        {"class:container", "id:pausebtn", "class:btn"}, df, thr)
    assert "id:pausebtn" in got
    assert "class:container" not in got  # générique + DF élevé
    assert "class:btn" not in got  # liste générique


def test_level3_extract_symbols_regex():
    """Sans ast-grep : regex détecte function + appels."""
    js = """
    function startTimer() { tick(); }
    const stopTimer = () => clearInterval(id);
    startTimer();
    console.log('x');
    """
    defines, uses = pg.extract_symbols("app.js", js, file_path=None)
    assert "sym:startTimer" in defines
    assert "sym:stopTimer" in defines
    assert "sym:startTimer" in uses or "sym:tick" in uses
    assert "sym:tick" in uses
    # console filtré côté related (GENERIC), mais peut apparaître en uses bruts
    assert "sym:if" not in uses


def test_related_level3_symbol_link(tmp_path):
    """Définition startTimer dans a.js, appel dans b.js → related L3."""
    root = tmp_path
    proj = root / "projects" / "chrono"
    proj.mkdir(parents=True)
    (proj / "timer.js").write_text(
        "function startTimer() { return 1; }\n",
        encoding="utf-8",
    )
    (proj / "ui.js").write_text(
        "startTimer();\nconsole.log('ok');\n",
        encoding="utf-8",
    )
    (proj / "noise.js").write_text(
        "console.log('noop');\nsetInterval(() => {}, 1000);\n",
        encoding="utf-8",
    )
    pg.reindex_project(str(root), "chrono")
    g = pg.get_graph(str(root), "chrono")
    assert "sym:startTimer" in g["files"]["timer.js"]["defines"]
    assert "sym:startTimer" in g["files"]["ui.js"]["uses"]
    rel = pg.get_related(str(root), "chrono", "ui.js")
    assert "timer.js" in rel["related"]
    assert "noise.js" not in rel["related"]
    assert rel["level"] == 3
    sym = pg.get_symbols(str(root), "chrono", "startTimer")
    assert "timer.js" in sym["defined_in"]
    assert "ui.js" in sym["referenced_in"]


def test_generic_symbols_filtered():
    df = {"sym:console": 2, "sym:startTimer": 2}
    got = pg._discriminative_idents(
        {"sym:console", "sym:startTimer"}, df, threshold=3)
    assert "sym:startTimer" in got
    assert "sym:console" not in got


if __name__ == "__main__":
    import tempfile
    test_html_scripts_and_links()
    test_js_import_require()
    test_css_import()
    test_level2_html_defines()
    test_level2_js_uses()
    test_level2_css_defines()
    test_discriminative_helpers()
    test_level3_extract_symbols_regex()
    test_generic_symbols_filtered()
    with tempfile.TemporaryDirectory() as d:
        test_related_roundtrip(Path(d))
    with tempfile.TemporaryDirectory() as d:
        test_related_level2_id_link(Path(d))
    with tempfile.TemporaryDirectory() as d:
        test_idf_filters_generic_container(Path(d))
    with tempfile.TemporaryDirectory() as d:
        test_related_level3_symbol_link(Path(d))
    print("project_graph L1+L2+L3+IDF tests OK")
