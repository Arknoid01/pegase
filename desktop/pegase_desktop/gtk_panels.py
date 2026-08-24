"""Panneaux GTK4 — mémoire, atlas, contextes."""

from __future__ import annotations

from typing import Any, Callable

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gtk  # noqa: E402

from pegase_desktop.atlas import TYPE_LABELS, EntityStore
from pegase_desktop.llm.config import CONTEXTS_DIR


class ContextPreview(Adw.Window):
    def __init__(
        self,
        parent: Gtk.Window,
        filename: str,
        on_unload: Callable[[str], None],
    ) -> None:
        super().__init__(transient_for=parent, modal=True)
        self.filename = filename
        self.set_title(filename)
        self.set_default_size(520, 420)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        box.set_margin_top(12)
        box.set_margin_bottom(12)
        box.set_margin_start(12)
        box.set_margin_end(12)
        self.set_content(box)

        short = filename.replace("-context.md", "").replace(".md", "")
        head = Gtk.Label(label=f"{short.upper()}  ·  {filename}")
        head.set_halign(Gtk.Align.START)
        box.append(head)

        scroll = Gtk.ScrolledWindow()
        scroll.set_vexpand(True)
        path = CONTEXTS_DIR / filename
        content = path.read_text(encoding="utf-8") if path.is_file() else "(fichier introuvable)"
        tv = Gtk.TextView()
        tv.set_wrap_mode(Gtk.WrapMode.WORD_CHAR)
        tv.set_editable(False)
        tv.get_buffer().set_text(content)
        scroll.set_child(tv)
        box.append(scroll)

        actions = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        actions.set_halign(Gtk.Align.END)
        unload = Gtk.Button(label="Décharger")
        unload.connect("clicked", lambda *_: self._unload(on_unload))
        close = Gtk.Button(label="Fermer")
        close.connect("clicked", lambda *_: self.close())
        actions.append(close)
        actions.append(unload)
        box.append(actions)

    def _unload(self, on_unload: Callable[[str], None]) -> None:
        on_unload(self.filename)
        self.close()


class MemoSidebar(Gtk.Box):
    WIDTH = 220

    def __init__(self) -> None:
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        self.add_css_class("sidebar")
        self.set_size_request(self.WIDTH, -1)

        title = Gtk.Label(label="SOUVENIRS")
        title.add_css_class("sidebar-title")
        title.set_halign(Gtk.Align.START)
        self.append(title)

        scroll = Gtk.ScrolledWindow()
        scroll.set_vexpand(True)
        self._list = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        scroll.set_child(self._list)
        self.append(scroll)

    def set_entries(self, entries: list[dict[str, Any]]) -> None:
        while (child := self._list.get_first_child()) is not None:
            self._list.remove(child)
        if not entries:
            lbl = Gtk.Label(label="Aucun souvenir injectable.")
            lbl.set_wrap(True)
            lbl.set_margin_start(8)
            lbl.set_halign(Gtk.Align.START)
            self._list.append(lbl)
            return
        for entry in entries:
            cat = str(entry.get("category") or "?")
            content = str(entry.get("content") or "").strip()
            preview = content[:140] + ("…" if len(content) > 140 else "")
            card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
            card.add_css_class("memo-card")
            cat_lbl = Gtk.Label(label=cat.upper())
            cat_lbl.set_halign(Gtk.Align.START)
            body = Gtk.Label(label=preview or "(vide)", wrap=True)
            body.set_halign(Gtk.Align.START)
            card.append(cat_lbl)
            card.append(body)
            self._list.append(card)


class AtlasPanel(Gtk.Box):
    def __init__(self) -> None:
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        self.add_css_class("atlas-panel")
        self._store = EntityStore()

        title = Gtk.Label(label="ATLAS")
        title.add_css_class("sidebar-title")
        title.set_halign(Gtk.Align.START)
        self.append(title)

        self._body = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        self.append(self._body)

    def refresh(self) -> None:
        while (child := self._body.get_first_child()) is not None:
            self._body.remove(child)
        entities = self._store.load_all()
        if not entities:
            lbl = Gtk.Label(label="Aucune entité — ~/.pegase/memory/entities.json")
            lbl.set_halign(Gtk.Align.START)
            self._body.append(lbl)
            return
        for ent in entities[:24]:
            type_label = TYPE_LABELS.get(ent.type, ent.type or "Entité")
            line = f"{type_label} · {ent.name}"
            if ent.aliases:
                line += f" ({', '.join(ent.aliases[:3])})"
            lbl = Gtk.Label(label=line)
            lbl.set_halign(Gtk.Align.START)
            lbl.add_css_class("memo-card")
            self._body.append(lbl)
