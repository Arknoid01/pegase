"""Palette et typographie Pégase Desktop — thèmes par mode."""

from __future__ import annotations

from dataclasses import dataclass, replace
from typing import Callable

from pegase_desktop.mode_store import PegaseMode, get_mode

_theme_listeners: list[Callable[[], None]] = []


@dataclass(frozen=True)
class Palette:
    void: str = "#0c1018"
    bg_deep: str = "#101620"
    bg_shell: str = "#151c28"
    bg_panel: str = "#1a2230"
    bg_chat: str = "#121820"
    bg_card: str = "#1e2838"
    bg_input: str = "#222c3c"

    accent: str = "#3dd8e4"
    accent_bright: str = "#c8fcff"
    accent_soft: str = "#6ecfe0"
    accent_dim: str = "#1590a0"
    accent_deep: str = "#0f2e38"
    accent_glow: str = "#3dd8e4"

    btn: str = "#1e4555"
    btn_hover: str = "#2a6878"
    btn_glow: str = "#88d0dd"

    text: str = "#f2f6fa"
    text_soft: str = "#d4dee8"
    text_muted: str = "#8a9cb0"
    text_faint: str = "#5c6d80"

    user_bg: str = "#1a3344"
    user_edge: str = "#3a88a0"
    pegase_bg: str = "#182030"
    pegase_edge: str = "#3dd8e4"
    system_bg: str = "#141c28"

    success: str = "#62ddb0"
    warn: str = "#efbf55"
    drive: str = "#ef8888"
    work: str = "#ddb055"

    line: str = "#243040"
    line_glow: str = "#2f5868"


NORMAL = Palette()

WORK = replace(
    NORMAL,
    accent="#d4a855",
    accent_bright="#f0c878",
    accent_soft="#b89040",
    accent_dim="#6a5530",
    accent_deep="#2a2010",
    accent_glow="#e8b84a",
    btn="#4a3820",
    btn_hover="#6a5030",
    btn_glow="#d4a855",
    user_bg="#2a2218",
    user_edge="#8a7040",
    pegase_bg="#1a1610",
    pegase_edge="#d4a855",
    line_glow="#5a4530",
)

DRIVE = replace(
    NORMAL,
    accent="#e8a0a0",
    accent_bright="#f8c0c0",
    accent_soft="#d08080",
    accent_dim="#7a4545",
    accent_deep="#2a1818",
    accent_glow="#e87a7a",
    btn="#552828",
    btn_hover="#703838",
    btn_glow="#e8a0a0",
    user_bg="#2a1818",
    user_edge="#904848",
    pegase_bg="#1a1212",
    pegase_edge="#e8a0a0",
    line_glow="#603030",
)

MODE_PALETTES = {
    PegaseMode.NORMAL: NORMAL,
    PegaseMode.WORK: WORK,
    PegaseMode.DRIVE: DRIVE,
}

_active_palette: Palette = NORMAL


def get_active_palette() -> Palette:
    return _active_palette


def palette_for_mode(mode: PegaseMode) -> Palette:
    return MODE_PALETTES.get(mode, NORMAL)


def apply_mode_theme(mode: PegaseMode | None = None) -> Palette:
    global _active_palette
    m = mode or get_mode()
    _active_palette = palette_for_mode(m)
    for cb in _theme_listeners:
        cb()
    return _active_palette


def on_theme_change(callback: Callable[[], None]) -> None:
    _theme_listeners.append(callback)


class _PaletteProxy:
    def __getattr__(self, name: str):
        return getattr(_active_palette, name)


P = _PaletteProxy()

apply_mode_theme(get_mode())


@dataclass(frozen=True)
class Fonts:
    """Descripteurs Pango/CSS — UI GTK."""

    display: str = "Sans Bold 26"
    title: str = "Sans Bold 16"
    subtitle: str = "Sans 11"
    body: str = "Sans 12"
    small: str = "Sans 10"
    mono: str = "Monospace 10"
    chip: str = "Sans Bold 9"
    label: str = "Sans Bold 10"

    @classmethod
    def load(cls, _root=None) -> "Fonts":
        return cls()
