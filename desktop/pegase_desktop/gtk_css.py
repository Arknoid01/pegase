"""CSS GTK4 — glassmorphism Pégase."""

from __future__ import annotations

from pegase_desktop.theme import Palette, get_active_palette


def palette_css(p: Palette | None = None) -> str:
    c = p or get_active_palette()
    return f"""
window.pegase-root,
window.pegase-glass {{
  background-color: transparent;
}}

/* ── Panneau glass ── */
.side-panel {{
  background-color: transparent;
}}

window.side-panel-left,
.side-panel-left {{
  border-right: 1px solid rgba(120, 220, 240, 0.22);
  box-shadow: 12px 0 48px rgba(0, 0, 0, 0.45);
  border-radius: 0 20px 20px 0;
}}

.side-panel-left .pegase-glass {{
  border-radius: 0 20px 20px 0;
}}

.side-panel-left .panel-status {{
  border-bottom-right-radius: 20px;
}}

.pegase-glass {{
  background: linear-gradient(
    168deg,
    rgba(18, 32, 52, 0.94) 0%,
    rgba(10, 20, 36, 0.97) 45%,
    rgba(8, 16, 28, 0.98) 100%
  );
  color: {c.text};
}}

.glass-shell {{
  background: transparent;
  min-height: 0;
}}

/* ── En-tête panneau (bannière visuelle) ── */
.panel-hero {{
  padding: 0;
  min-height: 0;
}}

.panel-hero-banner {{
  min-height: 58px;
  background: radial-gradient(
    ellipse 90% 70% at 50% 0%,
    rgba(45, 130, 160, 0.28) 0%,
    transparent 70%
  );
  border-bottom: 1px solid rgba(120, 180, 200, 0.1);
}}

.panel-hero-image {{
  filter: drop-shadow(0 0 18px rgba(77, 232, 242, 0.6));
}}

.panel-hero-title {{
  font-size: 16px;
  font-weight: 800;
  color: {c.text};
  letter-spacing: 0.01em;
}}

.panel-hero-subtitle {{
  font-size: 11px;
  font-style: italic;
  color: rgba(160, 195, 215, 0.75);
}}

.panel-menu-btn {{
  min-width: 34px;
  min-height: 34px;
  color: rgba(220, 240, 255, 0.85);
  border-radius: 10px;
  background-color: rgba(255, 255, 255, 0.05);
}}

.panel-menu-btn:hover {{
  background-color: rgba(255, 255, 255, 0.1);
}}

.thinking .panel-hero-image {{
  filter: drop-shadow(0 0 24px rgba(77, 232, 242, 0.85));
}}

/* ── Statut en ligne (au-dessus du composer) ── */
.panel-status {{
  padding: 12px 16px 8px;
  background: transparent;
  border-top: 1px solid rgba(120, 180, 200, 0.12);
}}

/* ── Puce profil persistante (bas de panneau) ── */
.profile-chip {{
  padding: 8px 16px 12px;
  background: transparent;
  border-radius: 0;
}}

.profile-chip:hover {{
  background-color: rgba(255, 255, 255, 0.04);
}}

/* ── Menu latéral ── */
.panel-menu-popover,
.panel-menu-popover contents {{
  background: transparent;
}}

.panel-menu-popover contents {{
  background-color: rgba(16, 26, 40, 0.98);
  background-image: linear-gradient(
    168deg,
    rgba(18, 32, 52, 0.98) 0%,
    rgba(8, 16, 28, 0.99) 100%
  );
  border: 1px solid rgba(120, 200, 220, 0.18);
  border-radius: 14px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.5);
  color: {c.text};
}}

.panel-menu {{
  min-width: 240px;
}}

.menu-section-title {{
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: rgba(140, 170, 200, 0.65);
}}

.menu-item {{
  padding: 0;
  border-radius: 12px;
  min-height: 40px;
}}

.menu-row {{
  padding: 8px 12px;
}}

.menu-row-label {{
  font-size: 14px;
  font-weight: 500;
  color: rgba(230, 240, 255, 0.92);
}}

.menu-item:hover {{
  background-color: rgba(255, 255, 255, 0.06);
}}

.menu-user-row {{
  padding: 6px 4px 2px;
  opacity: 0.85;
}}

.composer-compact {{
  padding-top: 8px;
  padding-bottom: 12px;
}}

.composer-compact .composer-shell {{
  margin-top: 0;
}}

.status-dot {{
  font-size: 14px;
  color: #5dffb0;
  text-shadow: 0 0 10px rgba(93, 255, 176, 0.7);
}}

.status-offline .status-dot {{
  color: #ef8888;
  text-shadow: 0 0 10px rgba(239, 136, 136, 0.5);
}}

.status-title {{
  font-size: 13px;
  font-weight: 700;
  color: #eef6ff;
}}

.status-sub {{
  font-size: 11px;
  font-style: italic;
  color: rgba(160, 185, 210, 0.8);
}}

.user-avatar {{
  min-width: 36px;
  min-height: 36px;
  color: rgba(200, 230, 255, 0.85);
  background-color: rgba(255, 255, 255, 0.06);
  border-radius: 999px;
  padding: 8px;
  border: 1px solid rgba(120, 200, 220, 0.2);
}}

.user-name {{
  font-size: 14px;
  font-weight: 600;
  color: #eef6ff;
}}

.user-chevron {{
  color: rgba(160, 185, 210, 0.7);
  min-width: 16px;
}}

/* ── Chat & contenu ── */
.pegase-root {{
  background-color: {c.void};
  color: {c.text};
}}

.context-bar {{
  background-color: rgba(0, 0, 0, 0.15);
  padding: 8px 14px;
  border-bottom: 1px solid rgba(120, 180, 200, 0.12);
}}

.chat-area {{
  background-color: transparent;
}}

.chat-area-panel {{
  min-height: 0;
}}

.chat-scroll {{
  background-color: transparent;
}}

.message-panel {{
  margin-bottom: 2px;
}}

.msg-row-user {{ margin-left: 8px; }}
.msg-row-pegase {{ margin-right: 8px; }}

.msg-avatar {{
  min-width: 32px;
  min-height: 32px;
  font-size: 11px;
  font-weight: 800;
  border-radius: 999px;
  padding: 8px 0;
}}

.msg-avatar-user {{
  background: rgba(58, 136, 160, 0.35);
  color: {c.text};
  border: 1px solid rgba(100, 180, 200, 0.25);
}}

.msg-avatar-pegase {{
  background: radial-gradient(circle at 30% 25%, {c.accent_bright}, {c.accent_dim});
  color: {c.void};
  box-shadow: 0 0 12px rgba(61, 216, 228, 0.3);
}}

.message-user {{
  background: rgba(30, 60, 80, 0.45);
  border: 1px solid rgba(80, 160, 190, 0.25);
  border-radius: 16px 16px 4px 16px;
  padding: 10px 14px;
}}

.message-pegase {{
  background: rgba(20, 35, 50, 0.55);
  border: 1px solid rgba(100, 180, 200, 0.18);
  border-radius: 16px 16px 16px 4px;
  padding: 10px 14px;
}}

.message-system {{
  background: rgba(15, 25, 40, 0.5);
  border: 1px solid rgba(100, 160, 180, 0.15);
  border-radius: 12px;
  padding: 8px 14px;
  margin: 6px 10px;
  color: rgba(180, 200, 220, 0.85);
  font-size: 11px;
}}

.msg-body {{
  font-size: 14px;
  line-height: 1.55;
  color: rgba(230, 240, 250, 0.95);
}}

.msg-meta {{
  font-size: 10px;
  color: rgba(160, 185, 210, 0.75);
  margin-top: 6px;
}}

.chip {{
  background: rgba(20, 80, 100, 0.35);
  color: {c.accent_bright};
  border-radius: 999px;
  padding: 5px 11px;
  font-size: 10px;
  font-weight: 600;
  border: 1px solid rgba(100, 200, 220, 0.25);
}}

.chip-muted {{
  color: rgba(140, 165, 190, 0.7);
  font-size: 11px;
  font-style: italic;
}}

.composer-area {{
  background: rgba(8, 16, 28, 0.55);
  padding: 12px 14px 14px;
  border-top: 1px solid rgba(120, 180, 200, 0.15);
}}

.composer-shell {{
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(120, 200, 220, 0.22);
  border-radius: 22px;
  padding: 5px 5px 5px 16px;
}}

.composer-shell:focus-within {{
  border-color: rgba(100, 230, 250, 0.55);
  box-shadow: 0 0 0 2px rgba(61, 216, 228, 0.12);
}}

.composer-entry {{
  background: transparent;
  color: #f0f8ff;
  font-size: 14px;
  border: none;
  min-height: 28px;
}}

.composer-hint {{
  color: rgba(140, 165, 190, 0.65);
  font-size: 10px;
  padding: 8px 8px 0;
}}

.send-btn {{
  background: linear-gradient(145deg, {c.accent}, {c.accent_dim});
  color: {c.void};
  border-radius: 999px;
  min-width: 38px;
  min-height: 38px;
  box-shadow: 0 0 16px rgba(61, 216, 228, 0.35);
}}

.send-btn:hover {{
  background: linear-gradient(145deg, {c.accent_bright}, {c.accent});
}}

/* ── Mode fenêtre classique (non panneau) ── */
.header-bar {{
  background: linear-gradient(180deg, {c.bg_shell}, {c.bg_deep});
  border-bottom: 1px solid {c.line};
  padding: 12px 14px;
}}

.title-main {{ font-size: 18px; font-weight: 800; color: {c.text}; }}
.title-sub {{ font-size: 11px; color: {c.text_muted}; }}

.status-pill {{
  background: {c.bg_card};
  color: {c.text_muted};
  border-radius: 999px;
  padding: 5px 10px;
  font-size: 10px;
  border: 1px solid {c.line};
}}

.status-ok {{ color: {c.success}; }}
.status-warn {{ color: {c.warn}; }}

.sidebar {{
  background: {c.bg_panel};
  border-right: 1px solid {c.line};
  border-radius: 10px;
  margin: 8px;
}}
"""


def apply_css(provider, p: Palette | None = None) -> None:
    import gi

    gi.require_version("Gdk", "4.0")
    gi.require_version("Gtk", "4.0")
    from gi.repository import Gdk, Gtk

    css = palette_css(p)
    provider.load_from_string(css)
    display = Gdk.Display.get_default()
    if display is None:
        return
    Gtk.StyleContext.add_provider_for_display(
        display,
        provider,
        Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION,
    )
