"""Splash au lancement — orbe Android qui s'allume."""

from __future__ import annotations

import tkinter as tk

from pegase_desktop.orb_draw import draw_orb_canvas
from pegase_desktop.theme import Fonts, get_active_palette


class SplashScreen:
    def __init__(self, root: tk.Tk, fonts: Fonts, on_done) -> None:
        self.root = root
        self.fonts = fonts
        self.on_done = on_done
        self._t = 0.0
        self._steps = 0

        P = get_active_palette()
        self.overlay = tk.Toplevel(root)
        self.overlay.overrideredirect(True)
        self.overlay.configure(bg="#0B0E14")
        self.overlay.attributes("-topmost", True)
        self.overlay.geometry(f"{root.winfo_screenwidth()}x{root.winfo_screenheight()}+0+0")

        self.canvas = tk.Canvas(
            self.overlay, bg="#0B0E14", highlightthickness=0, bd=0
        )
        self.canvas.pack(fill="both", expand=True)
        self._animate()

    def _animate(self) -> None:
        P = get_active_palette()
        self._t += 0.08
        self._steps += 1
        w = max(self.canvas.winfo_width(), 400)
        h = max(self.canvas.winfo_height(), 400)
        cx, cy = w // 2, h // 2
        self.canvas.delete("all")

        grow = min(1.0, self._steps / 12)
        r = int(36 * grow)
        if r > 4:
            draw_orb_canvas(
                self.canvas, cx, cy, r,
                t=self._t,
                thinking=self._steps > 6,
                tag="splash_orb",
            )

        self.canvas.create_text(
            cx, cy + 56, text="PÉGASE",
            fill=P.accent_bright, font=self.fonts.display,
        )

        if self._steps < 18:
            self.overlay.after(55, self._animate)
        else:
            self.overlay.after(120, self._finish)

    def _finish(self) -> None:
        self.overlay.destroy()
        self.on_done()
