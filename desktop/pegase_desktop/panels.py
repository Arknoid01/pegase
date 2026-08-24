"""Panneaux latéraux — mémoire, atlas, contextes."""

from __future__ import annotations

import tkinter as tk
from tkinter import scrolledtext
from typing import Any, Callable

from pegase_desktop.atlas import TYPE_LABELS, EntityStore
from pegase_desktop.llm.config import CONTEXTS_DIR
from pegase_desktop.theme import Fonts, P


class ContextPreview(tk.Toplevel):
    """Preview d'un fichier contexte .md."""

    def __init__(
        self,
        parent: tk.Misc,
        fonts: Fonts,
        filename: str,
        on_unload: Callable[[str], None],
    ) -> None:
        super().__init__(parent)
        self.filename = filename
        self.title(filename)
        self.configure(bg=P.bg_panel)
        self.geometry("520x420")
        self.minsize(360, 240)

        head = tk.Frame(self, bg=P.bg_shell)
        head.pack(fill="x", padx=12, pady=(12, 8))
        short = filename.replace("-context.md", "").replace(".md", "")
        tk.Label(
            head, text=short.upper(), font=fonts.label,
            fg=P.accent_bright, bg=P.bg_shell, anchor="w",
        ).pack(side="left")
        tk.Label(
            head, text=filename, font=fonts.small,
            fg=P.text_muted, bg=P.bg_shell, anchor="w",
        ).pack(side="left", padx=(10, 0))

        body = scrolledtext.ScrolledText(
            self, wrap="word", bg=P.bg_input, fg=P.text_soft,
            font=fonts.body, relief="flat", padx=12, pady=10,
            highlightthickness=1, highlightbackground=P.line,
        )
        body.pack(fill="both", expand=True, padx=12, pady=(0, 8))
        path = CONTEXTS_DIR / filename
        content = path.read_text(encoding="utf-8") if path.is_file() else "(fichier introuvable)"
        body.insert("1.0", content)
        body.configure(state="disabled")

        actions = tk.Frame(self, bg=P.bg_panel)
        actions.pack(fill="x", padx=12, pady=(0, 12))
        tk.Button(
            actions, text="Décharger", command=lambda: self._unload(on_unload),
            bg=P.btn, fg=P.text, relief="flat", padx=12, pady=4,
        ).pack(side="right")
        tk.Button(
            actions, text="Fermer", command=self.destroy,
            bg=P.bg_card, fg=P.text_muted, relief="flat", padx=12, pady=4,
        ).pack(side="right", padx=(0, 8))

    def _unload(self, on_unload: Callable[[str], None]) -> None:
        on_unload(self.filename)
        self.destroy()


class ContextChipBar(tk.Frame):
    """Chips contextes actifs — clic preview, clic droit décharger."""

    def __init__(
        self,
        parent: tk.Misc,
        fonts: Fonts,
        on_unload: Callable[[str], None],
        on_preview: Callable[[str], None] | None = None,
        **kwargs,
    ) -> None:
        super().__init__(parent, bg=P.bg_shell, **kwargs)
        self.fonts = fonts
        self.on_unload = on_unload
        self.on_preview = on_preview
        self._chip_frame = tk.Frame(self, bg=P.bg_shell)
        self._chip_frame.pack(fill="x", padx=12, pady=(0, 4))

    def set_contexts(self, filenames: list[str]) -> None:
        for w in self._chip_frame.winfo_children():
            w.destroy()
        if not filenames:
            tk.Label(
                self._chip_frame,
                text="Aucun contexte chargé — /load orion",
                font=self.fonts.small,
                fg=P.text_faint,
                bg=P.bg_shell,
            ).pack(side="left")
            return
        for name in filenames:
            short = name.replace("-context.md", "").replace(".md", "")
            chip = tk.Label(
                self._chip_frame,
                text=f"  {short}  ",
                font=self.fonts.chip,
                fg=P.accent_bright,
                bg=P.accent_deep,
                cursor="hand2",
                padx=4,
                pady=2,
            )
            chip.pack(side="left", padx=(0, 6))
            chip.bind("<Button-1>", lambda _e, n=name: self._open_preview(n))
            chip.bind("<Button-3>", lambda _e, n=name: self.on_unload(n))
            chip.bind("<Enter>", lambda _e, c=chip: c.configure(bg=P.accent_dim))
            chip.bind("<Leave>", lambda _e, c=chip: c.configure(bg=P.accent_deep))

    def _open_preview(self, filename: str) -> None:
        if self.on_preview:
            self.on_preview(filename)
        elif self.on_unload:
            self.on_unload(filename)


class MemoSidebar(tk.Frame):
    """Panneau latéral — souvenirs injectables dans le prompt."""

    WIDTH = 220

    def __init__(self, parent: tk.Misc, fonts: Fonts, **kwargs) -> None:
        super().__init__(
            parent,
            bg=P.bg_panel,
            width=self.WIDTH,
            highlightthickness=1,
            highlightbackground=P.line,
            **kwargs,
        )
        self.fonts = fonts
        self.pack_propagate(False)

        tk.Label(
            self,
            text="SOUVENIRS",
            font=fonts.label,
            fg=P.accent_bright,
            bg=P.bg_panel,
            anchor="w",
        ).pack(fill="x", padx=10, pady=(10, 6))

        self._list = tk.Frame(self, bg=P.bg_panel)
        self._list.pack(fill="both", expand=True, padx=8, pady=(0, 8))

    def set_entries(self, entries: list[dict[str, Any]]) -> None:
        for w in self._list.winfo_children():
            w.destroy()
        if not entries:
            tk.Label(
                self._list,
                text="Aucun souvenir injectable.",
                font=self.fonts.small,
                fg=P.text_faint,
                bg=P.bg_panel,
                wraplength=self.WIDTH - 24,
                justify="left",
                anchor="w",
            ).pack(fill="x")
            return
        for entry in entries:
            cat = str(entry.get("category") or "?")
            content = str(entry.get("content") or "").strip()
            preview = content[:140] + ("…" if len(content) > 140 else "")
            card = tk.Frame(
                self._list,
                bg=P.bg_card,
                highlightthickness=1,
                highlightbackground=P.line,
            )
            card.pack(fill="x", pady=(0, 6))
            tk.Label(
                card,
                text=cat.upper(),
                font=self.fonts.small,
                fg=P.accent_soft,
                bg=P.bg_card,
                anchor="w",
            ).pack(fill="x", padx=8, pady=(6, 0))
            tk.Label(
                card,
                text=preview or "(vide)",
                font=self.fonts.body,
                fg=P.text_soft,
                bg=P.bg_card,
                anchor="w",
                wraplength=self.WIDTH - 32,
                justify="left",
            ).pack(fill="x", padx=8, pady=(2, 8))


class AtlasPanel(tk.Frame):
    """Bandeau entités Atlas — ~/.pegase/memory/entities.json."""

    def __init__(self, parent: tk.Misc, fonts: Fonts, **kwargs) -> None:
        super().__init__(
            parent,
            bg=P.bg_shell,
            highlightthickness=1,
            highlightbackground=P.line_glow,
            **kwargs,
        )
        self.fonts = fonts
        self._store = EntityStore()

        head = tk.Frame(self, bg=P.bg_shell)
        head.pack(fill="x", padx=12, pady=(8, 4))
        tk.Label(
            head,
            text="ATLAS",
            font=fonts.label,
            fg=P.accent_bright,
            bg=P.bg_shell,
            anchor="w",
        ).pack(side="left")

        self._body = tk.Frame(self, bg=P.bg_shell)
        self._body.pack(fill="x", padx=12, pady=(0, 10))

    def refresh(self) -> None:
        for w in self._body.winfo_children():
            w.destroy()
        entities = self._store.load_all()
        if not entities:
            tk.Label(
                self._body,
                text="Aucune entité — ~/.pegase/memory/entities.json",
                font=self.fonts.small,
                fg=P.text_faint,
                bg=P.bg_shell,
                anchor="w",
            ).pack(fill="x")
            return
        for ent in entities[:24]:
            type_label = TYPE_LABELS.get(ent.type, ent.type or "Entité")
            line = f"{type_label} · {ent.name}"
            if ent.aliases:
                line += f" ({', '.join(ent.aliases[:3])})"
            tk.Label(
                self._body,
                text=line,
                font=self.fonts.small,
                fg=P.text_soft,
                bg=P.bg_card,
                anchor="w",
                padx=8,
                pady=4,
            ).pack(fill="x", pady=(0, 4))