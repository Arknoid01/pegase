"""Widgets GTK4 — chat, composer, en-tête."""

from __future__ import annotations

from typing import Callable, Literal

import gi

gi.require_version("Gdk", "4.0")
gi.require_version("Gtk", "4.0")
from gi.repository import Gdk, Gtk  # noqa: E402

Role = Literal["user", "pegase", "system"]


class StatusHeader(Gtk.Box):
    def __init__(
        self,
        on_sync_click: Callable[[], None] | None = None,
        on_close: Callable[[], None] | None = None,
        *,
        compact: bool = False,
    ) -> None:
        super().__init__(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        self.add_css_class("header-bar")
        if compact:
            self.add_css_class("header-compact")
        self._thinking = False

        if on_close:
            close = Gtk.Button()
            close.set_icon_name("sidebar-hide-symbolic")
            close.add_css_class("flat")
            close.add_css_class("header-close")
            close.set_tooltip_text("Replier le panneau (Super+P)")
            close.connect("clicked", lambda *_: on_close())
            self.append(close)

        orb_wrap = Gtk.Box()
        orb_wrap.add_css_class("header-orb-badge")
        orb_wrap.set_halign(Gtk.Align.CENTER)
        orb_wrap.set_valign(Gtk.Align.CENTER)
        avatar = Gtk.Label(label="P")
        avatar.add_css_class("header-orb")
        orb_wrap.append(avatar)
        self.append(orb_wrap)

        left = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        left.add_css_class("header-brand")
        left.set_halign(Gtk.Align.START)
        left.set_valign(Gtk.Align.CENTER)
        title = Gtk.Label(label="Pégase")
        title.add_css_class("title-main")
        title.set_halign(Gtk.Align.START)
        sub = Gtk.Label(label="assistant · mémoire partagée")
        sub.add_css_class("title-sub")
        sub.set_halign(Gtk.Align.START)
        left.append(title)
        if not compact:
            left.append(sub)
        self.append(left)

        spacer = Gtk.Box()
        spacer.set_hexpand(True)
        self.append(spacer)

        pills = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        pills.add_css_class("status-group")

        self._llm = Gtk.Label(label="● LLM")
        self._llm.add_css_class("status-pill")
        pills.append(self._llm)

        self._mode = Gtk.Label(label="normal")
        self._mode.add_css_class("status-pill")
        self._mode.set_visible(not compact)
        pills.append(self._mode)

        self._sync = Gtk.Label(label="sync —")
        self._sync.add_css_class("status-pill")
        if on_sync_click:
            sync_btn = Gtk.Button()
            sync_btn.add_css_class("flat")
            sync_btn.set_child(self._sync)
            sync_btn.set_tooltip_text("Synchroniser avec le téléphone")
            sync_btn.connect("clicked", lambda *_: on_sync_click())
            pills.append(sync_btn)
        else:
            pills.append(self._sync)

        self._stat = Gtk.Label(label="…")
        self._stat.add_css_class("status-pill")
        self._stat.set_visible(not compact)
        pills.append(self._stat)

        self.append(pills)

    def set_status(
        self,
        llm: str,
        mode: str,
        stat: str,
        sync: str = "",
        sync_color: str = "",
    ) -> None:
        self._llm.set_label(llm)
        self._mode.set_label(mode)
        if sync:
            self._sync.set_label(sync)
        self._stat.set_label(stat)
        self._llm.remove_css_class("status-ok")
        self._llm.remove_css_class("status-warn")
        if "●" in llm:
            self._llm.add_css_class("status-ok")
        else:
            self._llm.add_css_class("status-warn")

    def set_thinking(self, thinking: bool) -> None:
        self._thinking = thinking
        if thinking:
            self.add_css_class("thinking")
        else:
            self.remove_css_class("thinking")


class ContextChipBar(Gtk.Box):
    def __init__(
        self,
        on_unload: Callable[[str], None],
        on_preview: Callable[[str], None] | None = None,
    ) -> None:
        super().__init__(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        self.add_css_class("context-bar")
        self._on_unload = on_unload
        self._on_preview = on_preview
        self._flow = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        self.append(self._flow)

    def set_contexts(self, filenames: list[str]) -> None:
        while (child := self._flow.get_first_child()) is not None:
            self._flow.remove(child)
        if not filenames:
            lbl = Gtk.Label(label="Aucun contexte — /load orion")
            lbl.add_css_class("chip-muted")
            self._flow.append(lbl)
            return
        for name in filenames:
            short = name.replace("-context.md", "").replace(".md", "")
            btn = Gtk.Button(label=short)
            btn.add_css_class("chip")
            btn.connect("clicked", lambda _b, n=name: self._open(n))
            gesture = Gtk.GestureClick()
            gesture.set_button(Gdk.BUTTON_SECONDARY)
            gesture.connect("pressed", lambda _g, _n, _x, _y, n=name: self._on_unload(n))
            btn.add_controller(gesture)
            self._flow.append(btn)

    def _open(self, filename: str) -> None:
        if self._on_preview:
            self._on_preview(filename)


def _avatar_label(role: Role) -> str:
    return "V" if role == "user" else "P"


class ChatStream(Gtk.Box):
    def __init__(self, *, panel: bool = False, panel_width: int = 420) -> None:
        super().__init__(orientation=Gtk.Orientation.VERTICAL)
        self.add_css_class("chat-area")
        if panel:
            self.add_css_class("chat-area-panel")
        self._panel = panel
        self._bubble_chars = max(28, min(36, (panel_width - 96) // 10))
        self.set_vexpand(True)
        self.set_hexpand(True)
        self.set_valign(Gtk.Align.FILL)

        self._scroll = Gtk.ScrolledWindow()
        self._scroll.add_css_class("chat-scroll")
        self._scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        self._scroll.set_propagate_natural_width(False)
        self._scroll.set_vexpand(True)
        self._scroll.set_hexpand(True)
        self.append(self._scroll)

        self._inner = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        self._inner.set_hexpand(True)
        self._inner.set_halign(Gtk.Align.FILL)
        self._inner.set_margin_top(10 if panel else 14)
        self._inner.set_margin_bottom(10 if panel else 14)
        self._inner.set_margin_start(12 if panel else 14)
        self._inner.set_margin_end(12 if panel else 14)
        self._scroll.set_child(self._inner)

        self._stream_box: Gtk.Box | None = None
        self._stream_body: Gtk.Label | None = None

    def _configure_label(self, label: Gtk.Label, *, panel: bool | None = None) -> None:
        label.set_wrap(True)
        label.set_wrap_mode(Gtk.WrapMode.WORD)
        label.set_hexpand(False)
        label.set_halign(Gtk.Align.START)
        label.set_xalign(0.0)
        if panel if panel is not None else self._panel:
            label.set_max_width_chars(self._bubble_chars)

    def add_message(self, role: Role, text: str) -> Gtk.Label | None:
        if role == "system":
            self._system_pill(text)
            self._scroll_to_bottom()
            return None

        is_user = role == "user"
        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        row.add_css_class("msg-row")
        row.add_css_class("msg-row-user" if is_user else "msg-row-pegase")
        row.set_hexpand(True)
        row.set_halign(Gtk.Align.END if is_user else Gtk.Align.START)

        av = Gtk.Label(label=_avatar_label(role))
        av.add_css_class("msg-avatar")
        av.add_css_class("msg-avatar-user" if is_user else "msg-avatar-pegase")
        av.set_valign(Gtk.Align.START)

        bubble = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        bubble.add_css_class("message-user" if is_user else "message-pegase")
        if self._panel:
            bubble.add_css_class("message-panel")
            bubble.set_hexpand(False)
            bubble.set_halign(Gtk.Align.END if is_user else Gtk.Align.START)
        else:
            bubble.set_size_request(300, -1)

        body = Gtk.Label(label=text)
        body.add_css_class("msg-body")
        body.set_selectable(True)
        self._configure_label(body)
        bubble.append(body)

        if is_user:
            row.append(bubble)
            row.append(av)
        else:
            row.append(av)
            row.append(bubble)

        self._inner.append(row)
        self._scroll_to_bottom()
        return body

    def _system_pill(self, text: str) -> None:
        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        row.set_hexpand(True)
        row.set_halign(Gtk.Align.START)
        pill = Gtk.Label(label=text)
        pill.add_css_class("message-system")
        self._configure_label(pill)
        if not self._panel:
            pill.set_max_width_chars(52)
        row.append(pill)
        self._inner.append(row)

    def begin_stream(self) -> None:
        self._stream_body = self.add_message("pegase", "▁")
        if self._stream_body is not None:
            parent = self._stream_body.get_parent()
            if isinstance(parent, Gtk.Box):
                self._stream_box = parent

    def update_stream(self, text: str) -> None:
        if self._stream_body is not None:
            self._stream_body.set_label(text)
            self._scroll_to_bottom()

    def end_stream(self, text: str, meta: str = "") -> None:
        if self._stream_body is not None:
            self._stream_body.set_label(text)
            if meta and self._stream_box is not None:
                meta_lbl = Gtk.Label(label=meta)
                meta_lbl.add_css_class("msg-meta")
                self._configure_label(meta_lbl)
                self._stream_box.append(meta_lbl)
        self._stream_body = None
        self._stream_box = None
        self._scroll_to_bottom()

    def pulse_stream(self, frame: str) -> None:
        if self._stream_body is not None:
            self._stream_body.set_label(frame)

    def _scroll_to_bottom(self) -> None:
        adj = self._scroll.get_vadjustment()
        if adj is not None:
            adj.set_value(adj.get_upper())


class GlowComposer(Gtk.Box):
    def __init__(self, on_send: Callable[[], None], *, compact: bool = False) -> None:
        super().__init__(orientation=Gtk.Orientation.VERTICAL)
        self.add_css_class("composer-area")
        if compact:
            self.add_css_class("composer-compact")
        shell = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        shell.add_css_class("composer-shell")

        self.entry = Gtk.Entry()
        self.entry.add_css_class("composer-entry")
        self.entry.set_hexpand(True)
        self.entry.set_focusable(True)
        self.entry.set_can_focus(True)
        self.entry.set_placeholder_text("Message à Pégase…")
        self.entry.connect("activate", lambda *_: on_send())
        shell.append(self.entry)

        send = Gtk.Button()
        send.set_icon_name("send-alt-symbolic")
        send.add_css_class("send-btn")
        send.set_tooltip_text("Envoyer")
        send.connect("clicked", lambda *_: on_send())
        self._send_btn = send
        shell.append(send)
        self.append(shell)

        if not compact:
            hint = Gtk.Label(label="Entrée pour envoyer  ·  Super+P replier  ·  /help")
            hint.add_css_class("composer-hint")
            hint.set_halign(Gtk.Align.START)
            self.append(hint)

    def set_enabled(self, enabled: bool) -> None:
        self.entry.set_sensitive(enabled)
        self._send_btn.set_sensitive(enabled)

    def get_text(self) -> str:
        return self.entry.get_text()

    def clear(self) -> None:
        self.entry.set_text("")

    def focus_input(self) -> None:
        if not self.entry.get_sensitive():
            return
        self.entry.grab_focus()
        self.entry.select_region(-1, -1)
