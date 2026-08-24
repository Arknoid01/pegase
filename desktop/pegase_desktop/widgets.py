"""Widgets visuels — étoiles, aurora, bulles chat."""

from __future__ import annotations

import math
import random
import tkinter as tk
from typing import Callable, Literal

from pegase_desktop.orb_draw import ORB_MIDDLE, draw_orb_canvas
from pegase_desktop.theme import Fonts, P, on_theme_change

Role = Literal["user", "pegase", "system"]

PEGASE_QUOTES = [
    "Je suis Pégase. Pas ChatGPT. C'est mieux.",
    "La mémoire partagée, c'est la vraie magie.",
    "Entre Arch et Android, je garde le fil.",
    "Tu cliques une étoile ? T'es curieux. J'aime ça.",
    "Mode drive : je parle court, j'agis vite.",
    "Orion est chargé ? Alors on vise les étoiles.",
    "Sync ou pas sync, tel est le questionnement.",
    "Je réfléchis… enfin, je stream.",
]


def _hex_lerp(a: str, b: str, t: float) -> str:
    t = max(0.0, min(1.0, t))
    ar, ag, ab = int(a[1:3], 16), int(a[3:5], 16), int(a[5:7], 16)
    br, bg, bb = int(b[1:3], 16), int(b[3:5], 16), int(b[5:7], 16)
    r = int(ar + (br - ar) * t)
    g = int(ag + (bg - ag) * t)
    bl = int(ab + (bb - ab) * t)
    return f"#{r:02x}{g:02x}{bl:02x}"


class Starfield(tk.Canvas):
    """Fond étoilé drift — deux parallaxes + easter egg."""

    def __init__(
        self,
        parent: tk.Misc,
        on_star_click: Callable[[str], None] | None = None,
        **kwargs,
    ) -> None:
        super().__init__(parent, bg=P.void, highlightthickness=0, bd=0, **kwargs)
        self.on_star_click = on_star_click
        random.seed(42)
        self._far = [
            (random.random(), random.random(), random.uniform(0.08, 0.25))
            for _ in range(120)
        ]
        self._near = [
            (random.random(), random.random(), random.uniform(0.35, 0.9), random.random())
            for _ in range(45)
        ]
        self._phase = 0.0
        self.bind("<Configure>", lambda _e: self._draw())
        self.bind("<Button-1>", self._click)
        self.after(50, self._tick)

    def _click(self, event) -> None:
        if self.on_star_click:
            self.on_star_click(random.choice(PEGASE_QUOTES))

    def _tick(self) -> None:
        self._phase += 0.04
        self._draw()
        self.after(50, self._tick)

    def _draw(self) -> None:
        w = max(self.winfo_width(), 1)
        h = max(self.winfo_height(), 1)
        self.delete("all")
        self.configure(bg=P.void)

        for i, (nx, ny, r) in enumerate(
            [(0.22, 0.12, 0.18), (0.78, 0.18, 0.14), (0.5, 0.85, 0.12)]
        ):
            pulse = 0.85 + 0.15 * math.sin(self._phase + i * 1.7)
            rad = int(min(w, h) * r * pulse)
            cx, cy = int(nx * w), int(ny * h)
            for j in range(3, 0, -1):
                rr = int(rad * j / 3)
                stip = "gray12" if j == 1 else "gray25"
                self.create_oval(
                    cx - rr, cy - rr, cx + rr, cy + rr,
                    fill=P.accent_deep, outline=ORB_MIDDLE,
                    width=1, stipple=stip,
                )

        for x, y, speed in self._far:
            yy = (y + self._phase * speed * 0.02) % 1.0
            self.create_oval(int(x * w), int(yy * h), int(x * w) + 1, int(yy * h) + 1, fill=P.text_faint, outline="")

        for x, y, speed, tw in self._near:
            yy = (y + self._phase * speed * 0.015) % 1.0
            blink = 0.45 + 0.55 * (0.5 + 0.5 * math.sin(self._phase * 3 + tw * 6.28))
            size = 2 if blink > 0.75 else 1
            col = P.accent if blink > 0.8 else P.accent_soft
            self.create_oval(int(x * w), int(yy * h), int(x * w) + size, int(yy * h) + size, fill=col, outline="")


class AuroraHeader(tk.Canvas):
    """Bandeau aurora animé + orbe pulsant."""

    def __init__(
        self,
        parent: tk.Misc,
        fonts: Fonts,
        on_sync_click: Callable[[], None] | None = None,
        **kwargs,
    ) -> None:
        super().__init__(parent, height=96, bg=P.bg_shell, highlightthickness=0, bd=0, **kwargs)
        self.fonts = fonts
        self.on_sync_click = on_sync_click
        self._t = 0.0
        self._llm = "● LLM"
        self._mode = "normal"
        self._stat = "…"
        self._sync = "sync —"
        self._sync_color = P.text_muted
        self._sync_rect: tuple[int, int, int, int] | None = None
        self._thinking = False
        self.bind("<Configure>", lambda _e: self._paint())
        self.bind("<Button-1>", self._on_click)
        self.config(cursor="")
        self.after(40, self._animate)
        on_theme_change(self._paint)

    def set_status(
        self,
        llm: str,
        mode: str,
        stat: str,
        sync: str = "",
        sync_color: str = "",
    ) -> None:
        self._llm, self._mode, self._stat = llm, mode, stat
        if sync:
            self._sync = sync
        if sync_color:
            self._sync_color = sync_color
        self._paint()

    def set_thinking(self, thinking: bool) -> None:
        self._thinking = thinking

    def _on_click(self, event) -> None:
        if self.on_sync_click and self._sync_rect:
            x1, y1, x2, y2 = self._sync_rect
            if x1 <= event.x <= x2 and y1 <= event.y <= y2:
                self.on_sync_click()

    def _animate(self) -> None:
        speed = 0.14 if self._thinking else 0.06
        self._t += speed
        self._paint()
        self.after(40, self._animate)

    def _paint(self) -> None:
        w = max(self.winfo_width(), 400)
        h = max(self.winfo_height(), 96)
        self.delete("all")
        self.configure(bg=P.bg_shell)

        for i in range(4):
            pts: list[float] = []
            amp = (10 if self._thinking else 6) + i * 3
            base_y = 18 + i * 14
            for x in range(0, w + 20, 12):
                y = base_y + math.sin(x * 0.018 + self._t + i * 0.8) * amp
                pts.extend([x, y])
            if len(pts) >= 4:
                stip = "gray12" if i == 0 else "gray25"
                self.create_line(*pts, fill=P.accent if i == 1 else P.accent_dim, smooth=True, stipple=stip, width=2)

        cx, cy, r = 52, h // 2 + 4, 26
        draw_orb_canvas(
            self, cx, cy, r,
            t=self._t,
            thinking=self._thinking,
            tag="header_orb",
        )

        self.create_text(96, cy - 10, text="PÉGASE", anchor="w", fill=P.accent_bright, font=self.fonts.display)
        self.create_text(98, cy + 16, text="Desktop  ·  mémoire partagée", anchor="w", fill=P.text_muted, font=self.fonts.subtitle)

        self._pill(w - 360, cy - 14, self._llm, P.success if "●" in self._llm else P.warn, 90)
        self._pill(w - 260, cy - 14, self._mode, P.accent_soft, 80)
        self._sync_rect = self._pill(w - 160, cy - 14, self._sync, self._sync_color, 80)
        self._pill(w - 70, cy - 14, self._stat, P.text_muted, 120, anchor="e")
        self.create_line(0, h - 1, w, h - 1, fill=P.line_glow)

    def _pill(
        self,
        x: int,
        y: int,
        text: str,
        color: str,
        width: int = 90,
        anchor: str = "w",
    ) -> tuple[int, int, int, int]:
        tw = min(width, max(60, len(text) * 6 + 20))
        x1, x2 = (x - tw, x) if anchor == "e" else (x, x + tw)
        self.create_rectangle(x1, y, x2, y + 22, fill=P.accent_deep, outline=P.line_glow)
        self.create_text((x1 + x2) // 2, y + 11, text=text, fill=color, font=self.fonts.chip)
        return x1, y, x2, y + 22


class ChatStream(tk.Frame):
    """Fil de messages scrollable avec bulles + fade-in."""

    def __init__(
        self,
        parent: tk.Misc,
        fonts: Fonts,
        on_star_click: Callable[[str], None] | None = None,
        **kwargs,
    ) -> None:
        super().__init__(parent, bg=P.bg_chat, **kwargs)
        self.fonts = fonts
        self.on_star_click = on_star_click
        self._wrap = 440
        self._stream_body: tk.Label | None = None
        self._constellation_pts: list[tuple[int, int]] = []

        self.canvas = tk.Canvas(self, bg=P.bg_chat, highlightthickness=0, bd=0)
        self.scroll = tk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.inner = tk.Frame(self.canvas, bg=P.bg_chat)
        self._win = self.canvas.create_window((0, 0), window=self.inner, anchor="nw")

        self.canvas.pack(side="left", fill="both", expand=True)
        self.scroll.pack(side="right", fill="y")
        self.canvas.configure(yscrollcommand=self.scroll.set)

        self.inner.bind("<Configure>", self._sync_scroll)
        self.canvas.bind("<Configure>", self._on_resize)
        self.canvas.bind("<MouseWheel>", self._wheel)
        self.canvas.bind("<Button-4>", lambda _e: self.canvas.yview_scroll(-3, "units"))
        self.canvas.bind("<Button-5>", lambda _e: self.canvas.yview_scroll(3, "units"))
        self.canvas.bind("<Enter>", lambda _e: self.canvas.focus_set())
        self.canvas.bind("<Button-1>", self._constellation_click)

        self._draw_constellation()
        on_theme_change(self._redraw_theme)

    def _redraw_theme(self) -> None:
        self.configure(bg=P.bg_chat)
        self.canvas.configure(bg=P.bg_chat)
        self.inner.configure(bg=P.bg_chat)
        self._draw_constellation()

    def _on_resize(self, event) -> None:
        self.canvas.itemconfig(self._win, width=event.width)
        self._wrap = max(280, event.width - 160)
        self._draw_constellation()

    def _sync_scroll(self, _event=None) -> None:
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))
        self.canvas.yview_moveto(1.0)

    def _wheel(self, event) -> None:
        if event.delta:
            self.canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")

    def _draw_constellation(self) -> None:
        self.canvas.delete("constellation")
        w = max(self.canvas.winfo_width(), 300)
        h = max(self.canvas.winfo_height(), 400)
        random.seed(7)
        self._constellation_pts = [(random.randint(20, w - 20), random.randint(20, h - 20)) for _ in range(18)]
        for i, (x1, y1) in enumerate(self._constellation_pts):
            x2, y2 = self._constellation_pts[(i + 3) % len(self._constellation_pts)]
            if (x1 - x2) ** 2 + (y1 - y2) ** 2 < (w * 0.35) ** 2:
                self.canvas.create_line(x1, y1, x2, y2, fill=P.line, tags="constellation")
            self.canvas.create_oval(x1 - 3, y1 - 3, x1 + 3, y1 + 3, fill=P.accent_deep, outline=P.accent, width=1, tags=("constellation", "star"))
            self.canvas.create_oval(x1 - 1, y1 - 2, x1 + 1, y1, fill=P.accent_bright, outline="", tags=("constellation", "star"))
        self.canvas.tag_lower("constellation")

    def _constellation_click(self, event) -> None:
        items = self.canvas.find_overlapping(event.x - 6, event.y - 6, event.x + 6, event.y + 6)
        for item in items:
            if "star" in self.canvas.gettags(item) and self.on_star_click:
                self.on_star_click(random.choice(PEGASE_QUOTES))
                return

    def _fade_in(self, widget: tk.Widget, target_fg: str, target_bg: str, step: int = 0) -> None:
        if step >= 8:
            widget.configure(fg=target_fg, bg=target_bg)
            return
        t = step / 8
        fg = _hex_lerp(P.bg_chat, target_fg, t)
        bg = _hex_lerp(P.bg_chat, target_bg, t)
        try:
            widget.configure(fg=fg, bg=bg)
        except tk.TclError:
            pass
        widget.after(30, lambda: self._fade_in(widget, target_fg, target_bg, step + 1))

    def add_message(self, role: Role, text: str) -> tk.Label | None:
        if role == "system":
            self._system_pill(text)
            self._sync_scroll()
            return None

        row = tk.Frame(self.inner, bg=P.bg_chat)
        row.pack(fill="x", pady=5, padx=10)

        side = "right" if role == "user" else "left"
        anchor = "e" if role == "user" else "w"
        wrap = tk.Frame(row, bg=P.bg_chat)
        wrap.pack(side=side, anchor=anchor, padx=(48, 8) if role == "user" else (8, 48))

        edge = P.user_edge if role == "user" else P.pegase_edge
        bg = P.user_bg if role == "user" else P.pegase_bg
        shadow = tk.Frame(wrap, bg=P.line)
        shadow.pack(anchor=anchor, padx=(0 if role == "user" else 2, 2 if role == "user" else 0), pady=2)
        border = tk.Frame(shadow, bg=edge, padx=1, pady=1)
        border.pack(anchor=anchor)
        card = tk.Frame(border, bg=bg, padx=14, pady=10)
        card.pack()

        content = tk.Frame(card, bg=bg)
        content.pack(fill="both", expand=True)
        if role == "pegase":
            tk.Frame(content, bg=P.accent, width=3).pack(side="left", fill="y", padx=(0, 10))

        text_col = tk.Frame(content, bg=bg)
        text_col.pack(side="left", fill="both", expand=True)

        label_txt = "TOI" if role == "user" else "PÉGASE"
        label_color = P.accent_bright if role == "user" else P.accent
        lbl = tk.Label(text_col, text=label_txt, font=self.fonts.label, fg=label_color, bg=bg, anchor="w")
        lbl.pack(anchor="w")

        body = tk.Label(
            text_col, text=text, font=self.fonts.body,
            fg=P.text if role == "user" else P.text_soft, bg=bg,
            justify="left", anchor="w", wraplength=self._wrap,
        )
        body.pack(anchor="w", pady=(4, 0))
        self._fade_in(card, P.text if role == "user" else P.text_soft, bg)
        self._sync_scroll()
        return body

    def _system_pill(self, text: str) -> None:
        row = tk.Frame(self.inner, bg=P.bg_chat)
        row.pack(fill="x", pady=10)
        pill = tk.Frame(row, bg=P.system_bg, highlightbackground=P.line, highlightthickness=1)
        pill.pack(anchor="center")
        tk.Label(pill, text=text, font=self.fonts.small, fg=P.text_muted, bg=P.system_bg, padx=16, pady=6).pack()

    def begin_stream(self) -> None:
        self._stream_body = self.add_message("pegase", "▁")

    def update_stream(self, text: str) -> None:
        if self._stream_body is not None:
            self._stream_body.configure(text=text)
            self._sync_scroll()

    def end_stream(self, text: str, meta: str = "") -> None:
        if self._stream_body is not None:
            self._stream_body.configure(text=text)
            if meta:
                parent = self._stream_body.master
                tk.Label(parent, text=meta, font=self.fonts.small, fg=P.text_muted, bg=parent.cget("bg"), anchor="w").pack(anchor="w", pady=(6, 0))
        self._stream_body = None
        self._sync_scroll()

    def pulse_stream(self, frame: str) -> None:
        if self._stream_body is not None:
            self._stream_body.configure(text=frame)


class GlowComposer(tk.Frame):
    """Zone de saisie avec halo."""

    def __init__(self, parent: tk.Misc, fonts: Fonts, on_send, **kwargs) -> None:
        super().__init__(parent, bg=P.bg_deep, **kwargs)
        self.fonts = fonts
        self._on_send = on_send
        self._glow_t = 0.0

        self._glow_bar = tk.Canvas(self, height=3, bg=P.bg_deep, highlightthickness=0, bd=0)
        self._glow_bar.pack(fill="x", padx=18)
        self._paint_glow()

        shell = tk.Frame(self, bg=P.bg_card, highlightthickness=1, highlightbackground=P.line_glow)
        shell.pack(fill="x", padx=16, pady=(0, 4))
        self._shell = shell

        inner = tk.Frame(shell, bg=P.bg_input)
        inner.pack(fill="x", padx=2, pady=2)
        self._accent = tk.Frame(inner, bg=P.accent, width=4)
        self._accent.pack(side="left", fill="y")

        self.input = tk.Entry(
            inner, bg=P.bg_input, fg=P.text, insertbackground=P.accent_bright,
            relief="flat", font=fonts.body, highlightthickness=0,
        )
        self.input.pack(side="left", fill="x", expand=True, ipady=12, padx=(14, 8))
        self.input.bind("<Return>", self._send)
        self.input.bind("<FocusIn>", lambda _e: self._shell.configure(highlightbackground=P.accent))
        self.input.bind("<FocusOut>", lambda _e: self._shell.configure(highlightbackground=P.line_glow))

        self.btn = tk.Canvas(inner, width=44, height=44, bg=P.bg_input, highlightthickness=0, bd=0, cursor="hand2")
        self.btn.pack(side="right", padx=(0, 6), pady=4)
        self._draw_btn(P.btn)
        self.btn.bind("<Enter>", lambda _e: self._draw_btn(P.btn_hover))
        self.btn.bind("<Leave>", lambda _e: self._draw_btn(P.btn))
        self.btn.bind("<Button-1>", lambda _e: self._send())

        tk.Label(
            self, text="↵ envoyer  ·  Ctrl+L focus  ·  Esc stop  ·  /help",
            font=fonts.small, fg=P.text_faint, bg=P.bg_deep,
        ).pack(anchor="w", padx=20, pady=(6, 0))

        on_theme_change(self.refresh_theme)

    def refresh_theme(self) -> None:
        self.configure(bg=P.bg_deep)
        self._glow_bar.configure(bg=P.bg_deep)
        self._shell.configure(bg=P.bg_card, highlightbackground=P.line_glow)
        self._accent.configure(bg=P.accent)
        self.input.configure(bg=P.bg_input, fg=P.text, insertbackground=P.accent_bright)
        self.btn.configure(bg=P.bg_input)
        self._draw_btn(P.btn)

    def _paint_glow(self) -> None:
        self._glow_t += 0.05
        c = self._glow_bar
        c.delete("all")
        w = max(c.winfo_width(), 400)
        for i in range(5):
            x = int(w * (0.1 + i * 0.18 + 0.04 * math.sin(self._glow_t + i)))
            alpha = 0.3 + 0.7 * (0.5 + 0.5 * math.sin(self._glow_t * 2 + i))
            stip = "gray12" if alpha < 0.5 else "gray25" if alpha < 0.75 else "gray50"
            c.create_rectangle(x, 0, x + 60, 3, fill=P.accent_glow, outline="", stipple=stip)
        self.after(60, self._paint_glow)

    def _draw_btn(self, color: str) -> None:
        self.btn.delete("all")
        self.btn.create_oval(4, 4, 40, 40, fill=color, outline=P.accent, width=1)
        self.btn.create_text(22, 22, text="↑", fill=P.text, font=self.fonts.title)

    def _send(self, _e=None) -> None:
        self._on_send()

    def set_enabled(self, enabled: bool) -> None:
        self.input.configure(state="normal" if enabled else "disabled")
        self.btn.configure(cursor="hand2" if enabled else "arrow")
