"""Dessin orbe Pégase — aligné OrbView / MiniOrbView Android."""

from __future__ import annotations

import math
import tkinter as tk
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from tkinter import Canvas

# Couleurs officielles orbe (OrbThemes cyan + MiniOrbView)
ORB_CORE = "#B8FBF6"
ORB_MIDDLE = "#35D0DD"
ORB_EDGE = "#0B7D8F"
ORB_SPARK = "#D0FFFE"
ORB_GLOW = "#35D0DD"


def _hex_to_rgb(h: str) -> tuple[int, int, int]:
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


def _rgb_to_hex(r: int, g: int, b: int) -> str:
    return f"#{r:02x}{g:02x}{b:02x}"


def _lerp_color(a: str, b: str, t: float) -> str:
    t = max(0.0, min(1.0, t))
    ar, ag, ab = _hex_to_rgb(a)
    br, bg, bb = _hex_to_rgb(b)
    return _rgb_to_hex(
        int(ar + (br - ar) * t),
        int(ag + (bg - ag) * t),
        int(ab + (bb - ab) * t),
    )


def _gradient_color(t: float) -> str:
    if t <= 0.48:
        return _lerp_color(ORB_CORE, ORB_MIDDLE, t / 0.48)
    return _lerp_color(ORB_MIDDLE, ORB_EDGE, (t - 0.48) / 0.52)


def draw_orb_canvas(
    canvas: Canvas,
    cx: float,
    cy: float,
    radius: float,
    *,
    t: float = 0.0,
    thinking: bool = False,
    tag: str = "orb",
    pulse: float | None = None,
) -> None:
    """Orbe sphérique + halos + particules — style Android."""
    canvas.delete(tag)
    if radius < 2:
        return

    breath = pulse if pulse is not None else 0.5 + 0.5 * math.sin(t * 2)
    scale = 1.0 + breath * (0.12 if thinking else 0.075)
    r = radius * scale

    # Halos pulsants (3 anneaux)
    for i, factor in enumerate((1.42, 1.88, 2.38)):
        hp = 0.5 + 0.5 * math.sin(t * 2.5 + i * 1.1)
        hr = r * factor * (1.0 + hp * 0.08)
        alpha_stip = "gray12" if hp < 0.35 else "gray25" if hp < 0.65 else "gray50"
        canvas.create_oval(
            cx - hr, cy - hr, cx + hr, cy + hr,
            outline=ORB_MIDDLE, width=1 + hp * 1.2,
            stipple=alpha_stip, tags=tag,
        )

    # Particules orbitales (derrière)
    _draw_orbit_dots(canvas, cx, cy, r, t, tag, front=False)

    # Sphère — dégradé radial simulé
    steps = 14
    for i in range(steps, 0, -1):
        frac = i / steps
        ri = r * frac
        col = _gradient_color(1.0 - frac * 0.85)
        canvas.create_oval(cx - ri, cy - ri, cx + ri, cy + ri, fill=col, outline="", tags=tag)

    # Reflet specular (pas croissant lune — petit highlight)
    hl = r * 0.22
    canvas.create_oval(
        cx - r * 0.38, cy - r * 0.42,
        cx - r * 0.38 + hl, cy - r * 0.42 + hl * 0.8,
        fill=ORB_CORE, outline="", stipple="gray50", tags=tag,
    )

    # Particules devant
    _draw_orbit_dots(canvas, cx, cy, r, t, tag, front=True)

    # Arc réflexion (thinking)
    if thinking:
        angle = (t * 120) % 360
        ar = r * 1.18
        canvas.create_arc(
            cx - ar, cy - ar, cx + ar, cy + ar,
            start=angle, extent=120,
            style="arc", outline=ORB_MIDDLE, width=max(2, r * 0.06),
            tags=tag,
        )
        canvas.create_arc(
            cx - ar, cy - ar, cx + ar, cy + ar,
            start=angle + 180, extent=60,
            style="arc", outline=ORB_MIDDLE, width=max(1, r * 0.04),
            stipple="gray25", tags=tag,
        )


def _draw_orbit_dots(
    canvas: Canvas,
    cx: float,
    cy: float,
    r: float,
    t: float,
    tag: str,
    *,
    front: bool,
) -> None:
    orbit_r = r * 2.4
    dot_r = max(1, r * 0.07)
    angle_base = t * 80
    for i in range(5):
        ang = math.radians(angle_base + i * 72)
        px = cx + math.cos(ang) * orbit_r
        py = cy + math.sin(ang) * orbit_r * 0.35
        is_front = py > cy
        if is_front == front:
            canvas.create_oval(
                px - dot_r, py - dot_r, px + dot_r, py + dot_r,
                fill=ORB_SPARK if front else ORB_MIDDLE,
                outline="", stipple="gray50" if not front else "",
                tags=tag,
            )


def draw_orb_pil(draw, cx: int, cy: int, radius: int) -> None:
    """Orbe pour icône tray PIL — fond transparent."""
    for i in range(radius, 0, -1):
        frac = i / radius
        col = _gradient_color(1.0 - frac * 0.85)
        draw.ellipse((cx - i, cy - i, cx + i, cy + i), fill=col)

    hl = max(2, radius // 5)
    draw.ellipse(
        (cx - radius // 3, cy - radius // 3, cx - radius // 3 + hl, cy - radius // 3 + hl),
        fill=ORB_CORE,
    )

    for factor in (1.35, 1.75):
        hr = int(radius * factor)
        draw.ellipse(
            (cx - hr, cy - hr, cx + hr, cy + hr),
            outline=ORB_MIDDLE,
            width=1,
        )


# Fond chroma pour transparence fenêtre flottante (Linux/X11)
ORB_CHROMA = "#010102"


def pil_available() -> bool:
    try:
        import PIL  # noqa: F401
        return True
    except ImportError:
        return False


def render_floating_orb(
    size: int,
    t: float,
    *,
    chroma: str = ORB_CHROMA,
    discreet: bool = True,
):
    """Frame orbe flottant — glow doux, halo respirant, fond chroma uniforme."""
    from PIL import Image, ImageDraw, ImageFilter

    cr, cg, cb = _hex_to_rgb(chroma)
    canvas = Image.new("RGBA", (size, size), (cr, cg, cb, 255))
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))

    cx, cy = size / 2.0, size / 2.0
    base_r = size * (0.34 if discreet else 0.36)
    breath = 0.5 + 0.5 * math.sin(t * 2.0)
    pulse = 0.055 if discreet else 0.08
    r = base_r * (1.0 + breath * pulse)

    mid = _hex_to_rgb(ORB_MIDDLE)
    core = _hex_to_rgb(ORB_CORE)
    spark = _hex_to_rgb(ORB_SPARK)

    # Glow diffus (comme BlurMaskFilter Android)
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(glow)
    glow_a = 32 + int(breath * 28)
    gr = r * (1.18 if discreet else 1.32)
    gdraw.ellipse((cx - gr, cy - gr, cx + gr, cy + gr), fill=(*mid, glow_a))
    glow = glow.filter(ImageFilter.GaussianBlur(radius=max(2.0, base_r * 0.22)))
    layer = Image.alpha_composite(layer, glow)

    draw = ImageDraw.Draw(layer)

    # Halos respirants — deux ondes décalées
    wave = (t * 0.35) % 1.0
    for phase_off, spread, peak_a, stroke in (
        (0.0, 0.55 if discreet else 0.65, 95 if discreet else 110, 0.06),
        (0.45, 0.42 if discreet else 0.52, 55 if discreet else 70, 0.045),
    ):
        w = (wave + phase_off) % 1.0
        halo_r = base_r * (1.12 + w * spread)
        alpha = int((1.0 - w) * peak_a)
        if alpha > 4:
            draw.ellipse(
                (cx - halo_r, cy - halo_r, cx + halo_r, cy + halo_r),
                outline=(*mid, alpha),
                width=max(1, int(base_r * stroke * (1.0 + breath * 0.35))),
            )

    # Sphère — dégradé radial avec bord adouci
    steps = 18
    for i in range(steps, 0, -1):
        frac = i / steps
        ri = r * frac
        col = _hex_to_rgb(_gradient_color(1.0 - frac * 0.88))
        edge_a = 255 if frac > 0.12 else int(255 * (frac / 0.12))
        draw.ellipse(
            (cx - ri, cy - ri, cx + ri, cy + ri),
            fill=(*col, edge_a),
        )

    # Reflet specular doux
    hl_w = r * 0.24
    hl_h = hl_w * 0.72
    draw.ellipse(
        (cx - r * 0.34, cy - r * 0.41, cx - r * 0.34 + hl_w, cy - r * 0.41 + hl_h),
        fill=(*core, 150),
    )

    # Étincelle orbitale unique (plus naturelle que 5 points)
    orbit_r = base_r * (0.92 if discreet else 1.05)
    ang = math.radians(t * 72)
    sx = cx + math.cos(ang) * orbit_r
    sy = cy + math.sin(ang) * orbit_r * 0.72
    spark_r = base_r * 0.09 * (0.78 + breath * 0.42)
    draw.ellipse(
        (sx - spark_r * 1.7, sy - spark_r * 1.7, sx + spark_r * 1.7, sy + spark_r * 1.7),
        fill=(*spark, 70),
    )
    draw.ellipse(
        (sx - spark_r, sy - spark_r, sx + spark_r, sy + spark_r),
        fill=(*spark, 185 if discreet else 210),
    )

    out = Image.alpha_composite(canvas, layer)
    return out.convert("RGB")


def floating_orb_widget(
    parent: tk.Misc,
    size: int = 96,
    *,
    chroma: str | None = None,
    **kwargs,
) -> tk.Label | tk.Canvas:
    """Label animé PIL (préféré) ou canvas Tk si Pillow absent."""
    bg = chroma or ORB_CHROMA
    if not pil_available():
        return orb_canvas_widget(parent, size=size, chroma=bg, **kwargs)

    from PIL import ImageTk

    label = tk.Label(parent, bg=bg, bd=0, highlightthickness=0, **kwargs)
    phase = {"t": 0.0}
    photo: dict[str, ImageTk.PhotoImage | None] = {"ref": None}

    def tick() -> None:
        phase["t"] += 0.05
        frame = render_floating_orb(size, phase["t"], chroma=bg)
        photo["ref"] = ImageTk.PhotoImage(frame)
        label.configure(image=photo["ref"])
        label.after(42, tick)

    tick()
    return label


def orb_canvas_widget(
    parent: tk.Misc,
    size: int = 72,
    *,
    chroma: str | None = None,
    **kwargs,
) -> tk.Canvas:
    """Mini canvas avec orbe animé (tray flottant)."""
    bg = chroma or "#0B0E14"
    c = tk.Canvas(
        parent, width=size, height=size,
        bg=bg, highlightthickness=0, bd=0, **kwargs,
    )
    phase = {"t": 0.0}

    def tick() -> None:
        phase["t"] += 0.06
        draw_orb_canvas(c, size / 2, size / 2, size * 0.32, t=phase["t"])
        c.after(50, tick)

    tick()
    return c
