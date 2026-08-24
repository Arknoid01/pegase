"""Fenêtre chat Pégase Desktop — GTK4 / libadwaita."""

from __future__ import annotations

import os
import sys
import threading
from typing import Callable

import gi

gi.require_version("Adw", "1")
gi.require_version("Gtk", "4.0")
gi.require_version("Gio", "2.0")
from gi.repository import Adw, Gio, GLib, Gtk  # noqa: E402

from pegase_desktop.commands_store import CommandStore
from pegase_desktop.drive_export import DriveExporter, DriveExportError
from pegase_desktop.drive_sync import SYNC_INTERVAL_S, DriveSyncEngine
from pegase_desktop.exec_intent import check_danger, log_exec, parse_exec_proposal
from pegase_desktop.gtk_css import apply_css
from pegase_desktop.gtk_panel import SidePanelController
from pegase_desktop.gtk_nav import PanelFooter, PanelHero, ProfileChip, build_panel_menu
from pegase_desktop.gtk_panels import AtlasPanel, ContextPreview, MemoSidebar
from pegase_desktop.gtk_tray import TrayController
from pegase_desktop.gtk_widgets import ChatStream, ContextChipBar, GlowComposer, StatusHeader
from pegase_desktop.llm.chat import ChatEngine
from pegase_desktop.llm.client import CancelToken, LlmCancelledError, LlmError
from pegase_desktop.llm.config import CONTEXTS_DIR, load_profile
from pegase_desktop.memory_store import MemoryStore
from pegase_desktop.mode_store import PegaseMode, get_mode, set_mode
from pegase_desktop.notifications import notify
from pegase_desktop.notepad_store import NotepadStore
from pegase_desktop.settings_store import panel_mode, panel_width, set_sound_enabled, sound_enabled
from pegase_desktop.single_instance import cleanup, start_listener
from pegase_desktop.sound import play_stream_done
from pegase_desktop.sync_status import mark_synced, sync_badge_color, sync_badge_text
from pegase_desktop.terminal_launch import launch_in_terminal
from pegase_desktop.theme import apply_mode_theme, on_theme_change

HELP = (
    "Bienvenue sur Pégase Desktop.\n"
    "Parle librement — réponses en streaming.\n\n"
    "Commandes :\n"
    "  /memo · /note · /list memo · /list notes\n"
    "  /load orion · /unload · /contexts\n"
    "  /mode normal|work|drive · /clear · /reload\n"
    "  /panel memo|atlas · /run cmd · /sound on|off · /help\n"
    "  /cmd save nom cmd · /cmd nom · /cmd list · /cmd rm nom"
)

APP_ID = "com.pegase.desktop"


class DesktopWindow(Adw.ApplicationWindow):
    def __init__(self, app: Adw.Application) -> None:
        super().__init__(application=app, title="Pégase")
        self.set_icon_name("pegase-desktop")
        self._tray: TrayController | None = None
        self._instance_server = start_listener(
            on_show=lambda: GLib.idle_add(self._external_command, "show"),
            on_toggle=lambda: GLib.idle_add(self._external_command, "toggle"),
            on_quit=lambda: GLib.idle_add(self._quit_from_socket),
        )

        self._panel_mode = panel_mode()
        if not self._panel_mode:
            self.set_default_size(720, 680)
            self.set_size_request(480, 520)
        self.add_css_class("pegase-root")
        if self._panel_mode:
            self.add_css_class("pegase-glass")

        self.memory = MemoryStore()
        self.notepad = NotepadStore()
        self.commands = CommandStore()
        self.drive = DriveExporter()
        self.sync_engine = DriveSyncEngine(
            exporter=self.drive,
            memory=self.memory,
            notepad=self.notepad,
        )
        self.chat = ChatEngine(memory=self.memory, notepad=self.notepad, drive=self.drive)
        self._sync_running = False
        self._generating = False
        self._pulse_source: int | None = None
        self._pulse_tick = 0
        self._cancel: CancelToken | None = None
        self._sync_source: int | None = None
        self._memo_visible = False
        self._atlas_visible = False
        self._ready = False
        self._css_provider = Gtk.CssProvider()

        apply_mode_theme(get_mode())
        self._build_chrome()
        self._bind_keys()

        apply_css(self._css_provider)
        on_theme_change(lambda: apply_css(self._css_provider))

        self.panel = SidePanelController(self) if self._panel_mode else None

        self.tray = TrayController(
            self,
            on_show=self._on_show,
            on_quit=self._quit_app,
            panel=self.panel,
        )
        self._tray = self.tray
        self.tray.bind_close()
        self.tray.start()

        GLib.idle_add(self._after_splash)

    def _external_command(self, action: str) -> bool:
        if self._tray is None:
            return False
        if action == "toggle":
            self._tray.toggle()
        else:
            self._tray.show()
        return False

    def _build_chrome(self) -> None:
        root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        root.add_css_class("glass-shell" if self._panel_mode else "")
        root.set_vexpand(True)
        root.set_hexpand(True)
        root.set_valign(Gtk.Align.FILL)
        self.set_content(root)

        if self._panel_mode:
            self.hero = PanelHero(on_menu=self._show_panel_menu)
            root.append(self.hero)
            self.header = None
        else:
            self.hero = None
            self.header = StatusHeader(on_sync_click=self._manual_sync)
            root.append(self.header)

        self.context_bar = ContextChipBar(
            on_unload=self._unload_context_chip,
            on_preview=self._preview_context,
        )
        if not self._panel_mode:
            root.append(self.context_bar)

        body = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        body.set_vexpand(True)
        body.set_hexpand(True)
        body.set_valign(Gtk.Align.FILL)
        root.append(body)

        self.memo_sidebar = MemoSidebar()
        self.main_col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.main_col.set_hexpand(True)
        self.main_col.set_vexpand(True)
        self.main_col.set_valign(Gtk.Align.FILL)
        body.append(self.main_col)

        self.stream = ChatStream(
            panel=self._panel_mode,
            panel_width=panel_width() if self._panel_mode else 420,
        )
        self.stream.set_vexpand(True)
        self.main_col.append(self.stream)

        self.atlas_panel = AtlasPanel()
        self.atlas_panel.set_visible(False)
        self.main_col.append(self.atlas_panel)

        if self._panel_mode:
            self.footer = PanelFooter()
            root.append(self.footer)
            profile_name = load_profile().user_name
            self.profile_chip = ProfileChip(name=profile_name, on_click=self._show_panel_menu)
            root.append(self.profile_chip)
            self.composer = GlowComposer(on_send=self._on_send, compact=True)
        else:
            self.footer = None
            self.profile_chip = None
            self.composer = GlowComposer(on_send=self._on_send)

        root.append(self.composer)

        self._body = body

    def _show_panel_menu(self, anchor: Gtk.Widget | None = None) -> None:
        if anchor is None:
            anchor = self.hero.menu_button if self.hero and self.hero.menu_button else self.hero
        pop = Gtk.Popover()
        pop.set_parent(anchor)
        pop.set_autohide(True)
        pop.set_has_arrow(False)
        pop.add_css_class("panel-menu-popover")

        def close_and(fn: Callable[[], None]) -> None:
            pop.popdown()
            fn()

        box = build_panel_menu(
            on_nav=lambda nav_id: close_and(lambda: self._on_nav_select(nav_id)),
            on_help=lambda: self._append("system", "Voir /help pour les commandes."),
            on_sync=self._manual_sync,
            on_hide=self._hide_panel,
            on_quit=self._quit_app,
        )
        pop.set_child(box)
        pop.popup()

    def _on_nav_select(self, nav_id: str) -> None:
        if nav_id == "assistant":
            self.stream.set_visible(True)
            self.atlas_panel.set_visible(False)
            self._atlas_visible = False
            GLib.idle_add(lambda: self.composer.focus_input() or False)
        elif nav_id == "memo":
            self._toggle_memo_panel()
        elif nav_id == "atlas":
            self._toggle_atlas_panel()
        elif nav_id == "sync":
            self._manual_sync()

    def _bind_keys(self) -> None:
        ctrl_l = Gtk.ShortcutController()
        ctrl_l.set_scope(Gtk.ShortcutScope.GLOBAL)
        for accel in ("<Control>l", "<Control>L"):
            ctrl_l.add_shortcut(
                Gtk.Shortcut.new(
                    Gtk.ShortcutTrigger.parse_string(accel),
                    Gtk.CallbackAction.new(lambda *_: self.composer.focus_input() or True),
                )
            )
        for accel in ("<Control>k", "<Control>K"):
            ctrl_l.add_shortcut(
                Gtk.Shortcut.new(
                    Gtk.ShortcutTrigger.parse_string(accel),
                    Gtk.CallbackAction.new(lambda *_: self._cmd_clear() or True),
                )
            )
        esc = Gtk.ShortcutController()
        esc.add_shortcut(
            Gtk.Shortcut.new(
                Gtk.ShortcutTrigger.parse_string("Escape"),
                Gtk.CallbackAction.new(lambda *_: self._stop_generation() or True),
            )
        )
        self.add_controller(ctrl_l)
        self.add_controller(esc)

    def _hide_panel(self) -> None:
        if self.panel is not None:
            self.panel.hide()

    def _after_splash(self) -> None:
        self._ready = True
        if self._panel_mode:
            self._append("system", "Parle librement — tape /help pour les commandes.")
        else:
            self._append("system", HELP)
            self._show_snapshot()
        self._refresh_status()
        self._refresh_context_chips()
        self._refresh_memo_panel()
        if self.panel is not None:
            self.panel.show()
        GLib.idle_add(self._focus_composer_idle)
        self._run_sync_async(manual=False)
        self._start_sync_timer()
        return False

    def _focus_composer_idle(self) -> bool:
        self.composer.focus_input()
        return False

    def _cmd_clear(self) -> None:
        if self._generating:
            return
        self.chat.clear_history()
        self._append("system", "Historique effacé (Ctrl+K).")

    def _on_show(self) -> None:
        GLib.idle_add(self._focus_composer_idle)

    def _quit_from_socket(self) -> None:
        self.tray.quit_app()

    def _quit_app(self) -> None:
        if self._sync_source is not None:
            GLib.source_remove(self._sync_source)
        if self._pulse_source is not None:
            GLib.source_remove(self._pulse_source)
        try:
            self._instance_server.close()
        except OSError:
            pass
        cleanup()
        self.get_application().quit()

    def _start_sync_timer(self) -> None:
        self._refresh_status()
        if self._sync_source is not None:
            GLib.source_remove(self._sync_source)
        self._sync_source = GLib.timeout_add_seconds(SYNC_INTERVAL_S, self._sync_timer_tick)

    def _sync_timer_tick(self) -> bool:
        self._run_sync_async(manual=False)
        return True

    def _manual_sync(self) -> None:
        self._run_sync_async(manual=True)

    def _run_sync_async(self, *, manual: bool) -> None:
        if self._sync_running:
            return
        self._sync_running = True

        def worker() -> None:
            result = self.sync_engine.run_once()

            def done() -> None:
                self._sync_running = False
                self._refresh_status()
                self._refresh_memo_panel()
                if manual or result.applied > 0:
                    self._refresh_context_chips()
                if manual:
                    if result.error:
                        self._append("system", f"Sync — {result.error}")
                    else:
                        self._append(
                            "system",
                            f"Sync OK — {result.applied} importée(s), {result.exported} exportée(s)",
                        )
                        if result.applied > 0:
                            notify("Pégase", f"{result.applied} changement(s) depuis le tel")
                elif result.applied > 0:
                    self._append("system", f"Sync tel → {result.applied} mise(s) à jour")

            GLib.idle_add(done)

        threading.Thread(target=worker, daemon=True).start()

    def _preview_context(self, filename: str) -> None:
        dlg = ContextPreview(self, filename, on_unload=self._unload_context_chip)
        dlg.present()

    def _refresh_status(self) -> None:
        mode = get_mode().value.lower()
        llm_ok = self.chat.is_configured()
        mem = len(self.memory.load())
        notes = len(self.notepad.load_items())
        sync_txt = sync_badge_text()
        stat = f"{mem} mem · {notes} notes"

        if self.header is not None:
            llm = "● LLM" if llm_ok else "○ LLM"
            self.header.set_status(
                llm, mode, stat,
                sync=sync_txt,
                sync_color=sync_badge_color(),
            )
        if self.footer is not None:
            detail = f"{stat} · {sync_txt}" if sync_txt else stat
            self.footer.set_online(ok=llm_ok, detail=detail if llm_ok else "Clé API manquante")

    def _refresh_context_chips(self) -> None:
        self.context_bar.set_contexts(self.chat.contexts.loaded_filenames())

    def _refresh_memo_panel(self) -> None:
        self.memo_sidebar.set_entries(self.memory.list_injectable(12))

    def _unload_context_chip(self, filename: str) -> None:
        self.chat.contexts.unload(filename)
        self._refresh_context_chips()
        self._append("system", f"Contexte déchargé : {filename}")

    def _apply_mode_ui(self) -> None:
        apply_mode_theme(get_mode())
        apply_css(self._css_provider)
        self._refresh_status()

    def _toggle_memo_panel(self) -> None:
        if self._panel_mode:
            rows = self.memory.list_injectable(8)
            if not rows:
                self._append("system", "Aucun souvenir — /memo texte pour en ajouter.")
                return
            text = "\n".join(
                f"· [{e.get('category', '?')}] {e.get('content', '')[:100]}" for e in rows
            )
            self._append("system", f"Mémo récent :\n{text}")
            return
        self._memo_visible = not self._memo_visible
        if self._memo_visible:
            self._body.prepend(self.memo_sidebar)
            self._refresh_memo_panel()
        else:
            self._body.remove(self.memo_sidebar)

    def _toggle_atlas_panel(self) -> None:
        self._atlas_visible = not self._atlas_visible
        self.atlas_panel.set_visible(self._atlas_visible)
        if self._panel_mode:
            self.stream.set_visible(not self._atlas_visible)
        if self._atlas_visible:
            self.atlas_panel.refresh()

    def _append(self, speaker: str, text: str) -> None:
        if not self._ready and speaker != "system":
            return
        role = "user" if speaker == "user" else "pegase" if speaker == "pegase" else "system"
        self.stream.add_message(role, text.strip())

    def _begin_stream(self) -> None:
        if self.header is not None:
            self.header.set_thinking(True)
        elif self.hero is not None:
            self.hero.add_css_class("thinking")
        self.stream.begin_stream()
        self._start_pulse()

    def _update_stream(self, text: str) -> None:
        if text:
            self.stream.update_stream(text)

    def _finish_stream(self, text: str, meta: str = "", *, cancelled: bool = False) -> None:
        if self.header is not None:
            self.header.set_thinking(False)
        elif self.hero is not None:
            self.hero.remove_css_class("thinking")
        self._stop_pulse()
        self.stream.end_stream(text.strip(), meta.strip())
        if not cancelled:
            play_stream_done()

    def _start_pulse(self) -> None:
        self._pulse_tick = 0
        if self._pulse_source is not None:
            GLib.source_remove(self._pulse_source)
        self._pulse_source = GLib.timeout_add(180, self._pulse_step)

    def _pulse_step(self) -> bool:
        if not self._generating:
            return False
        frames = ("▁", "▂", "▃", "▄", "▅")
        self.stream.pulse_stream(frames[self._pulse_tick % len(frames)])
        self._pulse_tick += 1
        return True

    def _stop_pulse(self) -> None:
        if self._pulse_source is not None:
            GLib.source_remove(self._pulse_source)
            self._pulse_source = None

    def _set_input_enabled(self, enabled: bool) -> None:
        self.composer.set_enabled(enabled)
        self._generating = not enabled

    def _stop_generation(self) -> None:
        if self._cancel is not None:
            self._cancel.cancel()

    def _show_snapshot(self) -> None:
        hist = len(self.chat.history()) // 2
        ctx = len(self.chat.contexts.loaded_filenames())
        self._append(
            "system",
            f"{len(self.memory.load())} souvenirs · {len(self.notepad.load_items())} notes · "
            f"{hist} tours · {ctx} contexte(s)",
        )
        self._refresh_status()
        self._refresh_context_chips()
        self._refresh_memo_panel()

    def _on_send(self) -> None:
        if self._generating:
            return
        line = self.composer.get_text().strip()
        self.composer.clear()
        if not line:
            return
        self._append("user", line)

        if line.startswith("/"):
            try:
                reply, export_job = self._handle_command(line)
            except Exception as exc:
                reply, export_job = f"Erreur : {exc}", None
            self._append("pegase", reply)
            self._refresh_status()
            if export_job is not None:
                self._run_drive_export(*export_job)
            return

        if not self.chat.is_configured():
            self._append(
                "pegase",
                "Clé API manquante — ajoute GROQ_API_KEY dans ~/.pegase/llm/keys.env",
            )
            return

        self._set_input_enabled(False)
        self._cancel = CancelToken()
        self._begin_stream()
        self._run_llm_chat(line)

    def _run_llm_chat(self, user_message: str) -> None:
        partial_lock = threading.Lock()
        latest_partial = {"text": ""}
        cancel = self._cancel

        def on_partial(text: str) -> None:
            with partial_lock:
                latest_partial["text"] = text
            GLib.idle_add(lambda: self._update_stream(latest_partial["text"]))

        def worker() -> None:
            msg, meta_line, cancelled = "", "", False
            try:
                result = self.chat.send(user_message, on_partial=on_partial, cancel=cancel)
                meta = f"{result.provider} · {result.latency_ms} ms"
                if result.drive_op:
                    meta += f" · {result.drive_op}"
                msg, meta_line = result.reply, meta
            except LlmCancelledError:
                cancelled = True
                with partial_lock:
                    partial = latest_partial["text"].strip()
                msg = (partial + "\n[interrompu]") if partial else "[interrompu]"
                meta_line = ""
            except LlmError as exc:
                msg, meta_line = str(exc), ""
            except Exception as exc:
                msg, meta_line = f"Erreur LLM : {exc}", ""

            def finish() -> None:
                display_msg, proposal = (msg, None) if cancelled else parse_exec_proposal(msg)
                self._finish_stream(display_msg, meta_line, cancelled=cancelled)
                self._set_input_enabled(True)
                self._cancel = None
                self.composer.focus_input()
                if proposal:
                    self._handle_exec_proposal(proposal)

            GLib.idle_add(finish)

        threading.Thread(target=worker, daemon=True).start()

    def _run_drive_export(self, export_fn: str, record: dict, local_label: str) -> None:
        def worker() -> None:
            try:
                if export_fn == "memory":
                    op_path = self.drive.export_memory_upsert(record)
                elif export_fn == "notepad":
                    op_path = self.drive.export_notepad_upsert(record)
                elif export_fn == "context":
                    op_path = self.drive.export_context_upsert(record["filename"], record["content"])
                else:
                    op_path = None
                if op_path:
                    mark_synced("export")
                    msg = f"{local_label} — Drive {op_path.name}"
                    notify("Pégase", msg)
                else:
                    msg = f"{local_label} — export inconnu"
            except DriveExportError as exc:
                msg = f"{local_label} — Drive : {exc}"
            except Exception as exc:
                msg = f"{local_label} — {exc}"

            def done() -> None:
                self._append("pegase", msg)
                self._refresh_status()

            GLib.idle_add(done)

        threading.Thread(target=worker, daemon=True).start()

    def _export_context_file(self, filename: str) -> None:
        path = CONTEXTS_DIR / filename
        if not path.is_file():
            return
        content = path.read_text(encoding="utf-8")
        label = f"Contexte · {filename}"
        self._run_drive_export("context", {"filename": filename, "content": content}, label)

    def _confirm_run(self, cmd: str, *, heading: str = "Exécuter une commande ?") -> bool:
        confirmed = [False]
        loop = GLib.MainLoop()

        dlg = Adw.MessageDialog(
            transient_for=self,
            heading=heading,
            body=cmd,
        )
        dlg.add_response("cancel", "Annuler")
        dlg.add_response("ok", "Lancer")
        dlg.set_response_appearance("ok", Adw.ResponseAppearance.DESTRUCTIVE)
        dlg.set_default_response("ok")
        dlg.set_close_response("cancel")

        def on_response(_dialog, response: str) -> None:
            confirmed[0] = response == "ok"
            loop.quit()

        dlg.connect("response", on_response)
        dlg.present()
        loop.run()
        return confirmed[0]

    def _handle_exec_proposal(self, command: str) -> None:
        """Une commande proposée par le LLM ne fait jamais confiance seule :
        blocklist d'abord, puis confirmation systématique, puis exécution
        toujours visible dans un terminal — jamais silencieuse."""
        reason = check_danger(command)
        if reason:
            log_exec(command, source="llm", outcome="blocked", reason=reason)
            self._append(
                "system",
                f"Commande proposée par Pégase bloquée ({reason}) : {command}",
            )
            return
        if not self._confirm_run(command, heading="Pégase propose de lancer :"):
            log_exec(command, source="llm", outcome="cancelled")
            self._append("system", f"Commande proposée par Pégase annulée : {command}")
            return
        if launch_in_terminal(command):
            log_exec(command, source="llm", outcome="launched")
            self._append("system", f"Lancé dans un terminal : {command}")
        else:
            log_exec(command, source="llm", outcome="no-terminal")
            self._append("system", "Aucun terminal trouvé sur ce système.")

    def _handle_cmd_command(self, line: str) -> str:
        usage = "Usage : /cmd save nom cmd · /cmd nom · /cmd list · /cmd rm nom"
        parts = line.split(maxsplit=2)
        if len(parts) < 2:
            return usage
        sub = parts[1].strip()

        if sub.lower() == "list":
            rows = self.commands.list_sorted()
            if not rows:
                return "Aucune commande enregistrée — /cmd save nom cmd"
            return "\n".join(f"· {c['name']} → {c['command']}" for c in rows)

        if sub.lower() == "save":
            rest = parts[2].strip() if len(parts) > 2 else ""
            if not rest:
                return "Usage : /cmd save nom la-commande-à-lancer"
            if " " in rest:
                name, command = rest.split(maxsplit=1)
            else:
                # un seul mot : nom = commande (ex. /cmd save claude → lance `claude`)
                name = command = rest
            self.commands.add(name, command)
            return f"Commande « {name} » enregistrée — /cmd {name} pour la lancer."

        if sub.lower() == "rm":
            if len(parts) < 3:
                return "Usage : /cmd rm nom"
            name = parts[2].strip()
            if self.commands.remove(name):
                return f"Commande « {name} » supprimée."
            return f"Introuvable : {name}"

        entry = self.commands.get(sub)
        if entry is None:
            return f"Commande introuvable : {sub} — /cmd list"
        if not self._confirm_run(entry["command"]):
            log_exec(entry["command"], source="user", outcome="cancelled")
            return "Annulé."
        if launch_in_terminal(entry["command"]):
            log_exec(entry["command"], source="user", outcome="launched")
            return f"Lancé dans un terminal : {sub}"
        log_exec(entry["command"], source="user", outcome="no-terminal")
        return "Aucun terminal trouvé sur ce système."

    def _handle_command(self, line: str) -> tuple[str, tuple[str, dict, str] | None]:
        lower = line.lower()
        if lower in ("/help", "help", "?"):
            return HELP, None
        if lower == "/reload":
            self._show_snapshot()
            return "Fichiers relus.", None
        if lower == "/clear":
            self.chat.clear_history()
            return "Historique effacé.", None
        if lower.startswith("/sound"):
            parts = line.split()
            if len(parts) > 1 and parts[1].lower() in ("off", "0", "false"):
                set_sound_enabled(False)
                return "Son désactivé.", None
            if len(parts) > 1 and parts[1].lower() in ("on", "1", "true"):
                set_sound_enabled(True)
                return "Son activé.", None
            return f"Son : {'on' if sound_enabled() else 'off'} — /sound on|off", None
        if lower.startswith("/panel"):
            parts = line.split()
            if len(parts) < 2:
                return "Usage : /panel memo|atlas", None
            which = parts[1].lower()
            if which == "memo":
                self._toggle_memo_panel()
                return f"Panneau mémo {'ouvert' if self._memo_visible else 'fermé'}.", None
            if which == "atlas":
                self._toggle_atlas_panel()
                return f"Panneau atlas {'ouvert' if self._atlas_visible else 'fermé'}.", None
            return "Panneaux : memo, atlas", None
        if lower == "/memo":
            self._toggle_memo_panel()
            return f"Panneau mémo {'ouvert' if self._memo_visible else 'fermé'}.", None
        if lower == "/atlas":
            self._toggle_atlas_panel()
            return f"Panneau atlas {'ouvert' if self._atlas_visible else 'fermé'}.", None
        if lower.startswith("/run "):
            cmd = line[5:].strip()
            if not cmd:
                return "Usage : /run htop", None
            if not self._confirm_run(cmd):
                log_exec(cmd, source="user", outcome="cancelled")
                return "Annulé.", None
            if launch_in_terminal(cmd):
                log_exec(cmd, source="user", outcome="launched")
                return f"Lancé dans un terminal : {cmd}", None
            log_exec(cmd, source="user", outcome="no-terminal")
            return "Aucun terminal trouvé sur ce système.", None
        if lower.startswith("/cmd"):
            return self._handle_cmd_command(line), None
        if lower.startswith("/mode"):
            parts = line.split()
            if len(parts) < 2:
                return f"Mode : {get_mode().value} — /mode normal|work|drive", None
            try:
                set_mode(PegaseMode[parts[1].upper()])
            except KeyError:
                return "Modes : normal, work, drive.", None
            self._apply_mode_ui()
            return f"Mode → {get_mode().value}.", None
        if lower.startswith("/load "):
            kw = line[6:].strip()
            loaded = self.chat.contexts.load_keyword(kw)
            if loaded:
                self._refresh_context_chips()
                self._export_context_file(loaded)
                return f"Chargé : {loaded} — export Drive…", None
            return f"Introuvable : {kw}", None
        if lower.startswith("/unload"):
            parts = line.split(maxsplit=1)
            self.chat.contexts.unload(parts[1].strip() if len(parts) > 1 else "all")
            self._refresh_context_chips()
            return "Contextes déchargés.", None
        if lower == "/contexts":
            loaded = self.chat.contexts.loaded_filenames()
            avail = self.chat.contexts.list_files()
            return (
                f"Actifs : {', '.join(loaded) or 'aucun'}\n"
                f"Disponibles : {', '.join(avail[:10])}{'…' if len(avail) > 10 else ''}",
                None,
            )
        if lower.startswith("/memo "):
            entry = self.memory.add(line[6:].strip())
            self._refresh_memo_panel()
            label = f"Souvenir · {entry['id'][:8]}…"
            return f"{label} — export…", ("memory", entry, label)
        if lower.startswith("/note "):
            item = self.notepad.add(line[6:].strip())
            label = f"Note · {item['id'][:8]}…"
            return f"{label} — export…", ("notepad", item, label)
        if lower == "/list memo":
            rows = self.memory.list_injectable(8)
            if not rows:
                return "Aucun souvenir.", None
            return "\n".join(
                f"· [{e.get('category', '?')}] {e.get('content', '')[:100]}" for e in rows
            ), None
        if lower == "/list notes":
            rows = self.notepad.list_active(12)
            if not rows:
                return "Aucune note.", None
            return "\n".join(f"· {i.get('text', '')[:100]}" for i in rows), None
        return "Commande inconnue — /help", None


class PegaseApplication(Adw.Application):
    def __init__(self) -> None:
        super().__init__(
            application_id=APP_ID,
            flags=Gio.ApplicationFlags.NON_UNIQUE,
        )
        Adw.init()

    def do_startup(self) -> None:
        Adw.Application.do_startup(self)
        if os.environ.get("PEGASE_DEBUG"):
            remote = self.get_is_remote() if hasattr(self, "get_is_remote") else False
            print(f"[pegase] startup remote={remote}", file=sys.stderr, flush=True)

    def do_activate(self) -> None:
        if os.environ.get("PEGASE_DEBUG"):
            print("[pegase] activate — création fenêtre", file=sys.stderr, flush=True)
        style = Adw.StyleManager.get_default()
        style.set_color_scheme(Adw.ColorScheme.FORCE_DARK)
        win = self.props.active_window
        if not win:
            try:
                win = DesktopWindow(self)
            except Exception as exc:
                print(f"[pegase] ERREUR fenêtre : {exc}", file=sys.stderr, flush=True)
                raise
        if getattr(win, "panel", None) is not None:
            win.panel.show()
        else:
            win.present()


def run_app(*, toggle: bool = False) -> None:
    import os
    import sys

    from pegase_desktop.single_instance import claim_primary_instance, cleanup

    if not claim_primary_instance(toggle=toggle):
        return

    if os.environ.get("PEGASE_DEBUG"):
        print("[pegase] démarrage interface…", file=sys.stderr, flush=True)

    try:
        app = PegaseApplication()
        status = app.run(None)
        if os.environ.get("PEGASE_DEBUG"):
            print(f"[pegase] app.run() terminé (code {status})", file=sys.stderr, flush=True)
    finally:
        cleanup()


if __name__ == "__main__":
    run_app()
