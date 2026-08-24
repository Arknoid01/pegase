"""Navigation et chrome du panneau latéral Pégase."""

from __future__ import annotations

import getpass
import os
from pathlib import Path
from typing import Callable

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gtk  # noqa: E402

_ASSETS = Path(__file__).resolve().parent / "assets"
_PEGASUS_HERO = _ASSETS / "pegase-pegasus-hero.png"
_PEGASUS_SVG = _ASSETS / "pegase-pegasus.svg"

NavId = str


class PanelHero(Gtk.Box):
    """Bannière visuelle compacte — logo + menu."""

    def __init__(self, *, on_menu: Callable[[], None] | None = None) -> None:
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.add_css_class("panel-hero")
        self.set_vexpand(False)

        overlay = Gtk.Overlay()
        overlay.add_css_class("panel-hero-banner")
        self.append(overlay)

        center = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        center.set_halign(Gtk.Align.CENTER)
        center.set_valign(Gtk.Align.CENTER)
        center.set_margin_top(10)
        center.set_margin_bottom(10)

        logo_path = _PEGASUS_HERO if _PEGASUS_HERO.is_file() else _PEGASUS_SVG
        if logo_path.is_file():
            img = Gtk.Picture.new_for_filename(str(logo_path))
            img.set_can_shrink(True)
            img.set_content_fit(Gtk.ContentFit.CONTAIN)
            img.set_size_request(30, 30)
            img.add_css_class("panel-hero-image")
            center.append(img)

        text_col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        text_col.set_valign(Gtk.Align.CENTER)

        title = Gtk.Label(label="Pégase")
        title.add_css_class("panel-hero-title")
        title.set_halign(Gtk.Align.START)
        text_col.append(title)

        subtitle = Gtk.Label(label="Votre assistant personnel")
        subtitle.add_css_class("panel-hero-subtitle")
        subtitle.set_halign(Gtk.Align.START)
        text_col.append(subtitle)

        center.append(text_col)

        overlay.set_child(center)

        self._menu_btn: Gtk.Button | None = None
        if on_menu:
            menu = Gtk.Button()
            menu.set_icon_name("open-menu-symbolic")
            menu.add_css_class("flat")
            menu.add_css_class("panel-menu-btn")
            menu.set_tooltip_text("Menu")
            menu.set_halign(Gtk.Align.START)
            menu.set_valign(Gtk.Align.START)
            menu.set_margin_start(6)
            menu.set_margin_top(6)
            menu.connect("clicked", lambda *_: on_menu())
            self._menu_btn = menu
            overlay.add_overlay(menu)

    @property
    def menu_button(self) -> Gtk.Button | None:
        return self._menu_btn


class PanelFooter(Gtk.Box):
    """Statut en ligne — juste au-dessus du composer."""

    def __init__(self) -> None:
        super().__init__(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        self.add_css_class("panel-status")
        self.set_vexpand(False)

        dot = Gtk.Label(label="●")
        dot.add_css_class("status-dot")
        self._dot = dot

        status_col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=1)
        self._status_title = Gtk.Label(label="Pégase en ligne")
        self._status_title.add_css_class("status-title")
        self._status_title.set_halign(Gtk.Align.START)
        self._status_sub = Gtk.Label(label="Prêt à vous assister")
        self._status_sub.add_css_class("status-sub")
        self._status_sub.set_halign(Gtk.Align.START)
        status_col.append(self._status_title)
        status_col.append(self._status_sub)

        self.append(dot)
        self.append(status_col)

    def set_online(self, *, ok: bool, detail: str = "") -> None:
        if ok:
            self.remove_css_class("status-offline")
            self._status_title.set_label("Pégase en ligne")
            self._status_sub.set_label(detail or "Prêt à vous assister")
        else:
            self.add_css_class("status-offline")
            self._status_title.set_label("Pégase hors ligne")
            self._status_sub.set_label(detail or "Clé API manquante")


class ProfileChip(Gtk.Button):
    """Puce profil persistante en bas du panneau — ouvre le menu au clic."""

    def __init__(self, *, name: str, on_click: Callable[[Gtk.Widget], None]) -> None:
        super().__init__()
        self.add_css_class("flat")
        self.add_css_class("profile-chip")

        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)

        avatar = Gtk.Image.new_from_icon_name("avatar-default-symbolic")
        avatar.add_css_class("user-avatar")
        row.append(avatar)

        self._name_label = Gtk.Label(label=name)
        self._name_label.add_css_class("user-name")
        self._name_label.set_halign(Gtk.Align.START)
        self._name_label.set_hexpand(True)
        row.append(self._name_label)

        chevron = Gtk.Image.new_from_icon_name("pan-up-symbolic")
        chevron.add_css_class("user-chevron")
        row.append(chevron)

        self.set_child(row)
        self.connect("clicked", lambda _b: on_click(self))

    def set_name(self, name: str) -> None:
        self._name_label.set_label(name)


def build_panel_menu(
    *,
    on_nav: Callable[[NavId], None],
    on_help: Callable[[], None],
    on_sync: Callable[[], None],
    on_hide: Callable[[], None],
    on_quit: Callable[[], None],
) -> Gtk.Box:
    """Contenu du menu latéral (navigation + actions)."""
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
    box.add_css_class("panel-menu")
    box.set_margin_top(10)
    box.set_margin_bottom(10)
    box.set_margin_start(12)
    box.set_margin_end(12)

    nav_title = Gtk.Label(label="Navigation")
    nav_title.add_css_class("menu-section-title")
    nav_title.set_halign(Gtk.Align.START)
    nav_title.set_margin_bottom(4)
    box.append(nav_title)

    for nav_id, icon, label in (
        ("assistant", "chat-symbolic", "Assistant"),
        ("memo", "folder-symbolic", "Mémo"),
        ("atlas", "map-symbolic", "Atlas"),
        ("sync", "cloud-symbolic", "Cloud"),
    ):
        btn = _menu_row(icon, label, lambda _b, nid=nav_id: on_nav(nid))
        box.append(btn)

    sep = Gtk.Separator(orientation=Gtk.Orientation.HORIZONTAL)
    sep.set_margin_top(8)
    sep.set_margin_bottom(8)
    box.append(sep)

    actions_title = Gtk.Label(label="Actions")
    actions_title.add_css_class("menu-section-title")
    actions_title.set_halign(Gtk.Align.START)
    actions_title.set_margin_bottom(4)
    box.append(actions_title)

    for icon, label, action in (
        ("help-about-symbolic", "Aide (/help)", on_help),
        ("view-refresh-symbolic", "Synchroniser", on_sync),
        ("sidebar-hide-symbolic", "Replier (Super+P)", on_hide),
        ("application-exit-symbolic", "Quitter", on_quit),
    ):
        box.append(_menu_row(icon, label, lambda _b, fn=action: fn()))

    sep2 = Gtk.Separator(orientation=Gtk.Orientation.HORIZONTAL)
    sep2.set_margin_top(8)
    sep2.set_margin_bottom(6)
    box.append(sep2)

    user_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
    user_row.add_css_class("menu-user-row")
    user_row.append(Gtk.Image.new_from_icon_name("avatar-default-symbolic"))
    name = os.environ.get("PEGASE_USER_NAME") or getpass.getuser().capitalize()
    lbl = Gtk.Label(label=name)
    lbl.add_css_class("user-name")
    lbl.set_halign(Gtk.Align.START)
    lbl.set_hexpand(True)
    user_row.append(lbl)
    box.append(user_row)

    return box


def _menu_row(icon: str, label: str, on_click: Callable[[Gtk.Button], None]) -> Gtk.Button:
    row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
    row.add_css_class("menu-row")
    row.append(Gtk.Image.new_from_icon_name(icon))
    lbl = Gtk.Label(label=label)
    lbl.add_css_class("menu-row-label")
    lbl.set_halign(Gtk.Align.START)
    lbl.set_hexpand(True)
    row.append(lbl)

    btn = Gtk.Button()
    btn.add_css_class("flat")
    btn.add_css_class("menu-item")
    btn.set_child(row)
    btn.connect("clicked", on_click)
    return btn
