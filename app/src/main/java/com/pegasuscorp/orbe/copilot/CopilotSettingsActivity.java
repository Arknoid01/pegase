package com.pegasuscorp.orbe.copilot;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.pegasuscorp.orbe.notifications.NotificationAccess;

import java.util.HashSet;
import java.util.Set;

/**
 * Réglages mode copilote — listes blanches apps (analyse + notifications).
 */
public class CopilotSettingsActivity extends AppCompatActivity {

  private static final class AppPreset {
    final String label;
    final String packageName;

    AppPreset(String label, String packageName) {
      this.label = label;
      this.packageName = packageName;
    }
  }

  private static final AppPreset[] PRESETS = {
      new AppPreset("YouTube", CopilotPrefs.PKG_YOUTUBE),
      new AppPreset("Chrome", "com.android.chrome"),
      new AppPreset("Firefox", "org.mozilla.firefox"),
      new AppPreset("Gmail", "com.google.android.gm"),
      new AppPreset("WhatsApp", "com.whatsapp"),
      new AppPreset("Messages", "com.google.android.apps.messaging"),
      new AppPreset("Telegram", "org.telegram.messenger"),
      new AppPreset("Slack", "com.Slack"),
  };

  private float density;
  private LinearLayout contentHost;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    density = getResources().getDisplayMetrics().density;

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.parseColor("#F00B0E14"));
    root.setPadding(dp(14), dp(14), dp(14), dp(14));

    TextView title = new TextView(this);
    title.setText("🛸 Mode copilote");
    title.setTextColor(Color.WHITE);
    title.setTextSize(20);
    title.setTypeface(null, Typeface.BOLD);
    root.addView(title);

    TextView subtitle = new TextView(this);
    subtitle.setText("Listes blanches strictes — rien ne s'active sans ton accord");
    subtitle.setTextColor(Color.parseColor("#88FFFFFF"));
    subtitle.setTextSize(12);
    subtitle.setPadding(0, dp(4), 0, dp(12));
    root.addView(subtitle);

    contentHost = new LinearLayout(this);
    contentHost.setOrientation(LinearLayout.VERTICAL);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(contentHost, matchWrap());
    root.addView(scroll, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

    ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
      Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
      v.setPadding(dp(14) + bars.left, dp(14) + bars.top,
          dp(14) + bars.right, dp(14) + bars.bottom);
      return insets;
    });

    setContentView(root);
    rebuild();
  }

  @Override
  protected void onResume() {
    super.onResume();
    rebuild();
  }

  private void rebuild() {
    contentHost.removeAllViews();
    addSectionTitle("Général");
    addSwitch("Orbe toujours visible", CopilotPrefs.isAlwaysOn(this), (on) -> {
      CopilotPrefs.setAlwaysOn(this, on);
    });
    addSwitch("Analyse d'écran (apps autorisées)", CopilotPrefs.isScreenAnalysisEnabled(this), (on) -> {
      CopilotPrefs.setScreenAnalysisEnabled(this, on);
      if (on) CopilotClient.get().sync(this);
    });
    addSwitch("Overlay traduction", CopilotPrefs.isTranslationOverlayEnabled(this), (on) -> {
      CopilotPrefs.setTranslationOverlayEnabled(this, on);
      if (!on) TranslationOverlayService.hide(this);
    });
    addSwitch("Alertes notifications ciblées", CopilotPrefs.isNotificationCopilotEnabled(this), (on) -> {
      CopilotPrefs.setNotificationCopilotEnabled(this, on);
    });

    addSectionTitle("Permissions");
    addActionButton("Afficher par-dessus les apps", () -> {
      Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
          android.net.Uri.parse("package:" + getPackageName()));
      startActivity(i);
    });
    addActionButton("Service d'accessibilité Pégase", () -> AccessibilityAccess.openSettings(this));
    addActionButton("Accès aux notifications", () -> NotificationAccess.openSettings(this));

    addSectionTitle("Apps — analyse d'écran");
    addHint("Seules ces apps déclenchent lecture a11y / traduction.");
    Set<String> screenWl = new HashSet<>(CopilotPrefs.getWhitelist(this));
    for (AppPreset p : PRESETS) {
      boolean on = screenWl.contains(p.packageName);
      addSwitch(p.label, on, (checked) -> {
        Set<String> wl = new HashSet<>(CopilotPrefs.getWhitelist(this));
        if (checked) wl.add(p.packageName);
        else wl.remove(p.packageName);
        CopilotPrefs.setWhitelist(this, wl);
        if (checked && CopilotPrefs.PKG_YOUTUBE.equals(p.packageName)) {
          CopilotPrefs.setScreenAnalysisEnabled(this, true);
        }
      });
    }

    addSectionTitle("Apps — notifications copilote");
    addHint("Pégase t'alerte uniquement pour ces apps.");
    Set<String> notifWl = new HashSet<>(CopilotPrefs.getNotificationWhitelist(this));
    for (AppPreset p : PRESETS) {
      boolean on = notifWl.contains(p.packageName);
      addSwitch(p.label + " (notif)", on, (checked) -> {
        Set<String> wl = new HashSet<>(CopilotPrefs.getNotificationWhitelist(this));
        if (checked) wl.add(p.packageName);
        else wl.remove(p.packageName);
        CopilotPrefs.setNotificationWhitelist(this, wl);
        if (checked) CopilotPrefs.setNotificationCopilotEnabled(this, true);
      });
    }
  }

  private void addSectionTitle(String text) {
    TextView tv = new TextView(this);
    tv.setText(text);
    tv.setTextColor(Color.parseColor("#35D0DD"));
    tv.setTextSize(14);
    tv.setTypeface(null, Typeface.BOLD);
    tv.setPadding(0, dp(14), 0, dp(6));
    contentHost.addView(tv, matchWrap());
  }

  private void addHint(String text) {
    TextView tv = new TextView(this);
    tv.setText(text);
    tv.setTextColor(Color.parseColor("#88FFFFFF"));
    tv.setTextSize(11);
    tv.setPadding(0, 0, 0, dp(8));
    contentHost.addView(tv, matchWrap());
  }

  private void addSwitch(String label, boolean checked, SwitchListener listener) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    row.setPadding(dp(10), dp(8), dp(10), dp(8));
    row.setBackground(cardBg());
    LinearLayout.LayoutParams lp = matchWrap();
    lp.bottomMargin = dp(6);
    contentHost.addView(row, lp);

    TextView tv = new TextView(this);
    tv.setText(label);
    tv.setTextColor(Color.WHITE);
    tv.setTextSize(14);
    row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

    SwitchCompat sw = new SwitchCompat(this);
    sw.setChecked(checked);
    sw.setOnCheckedChangeListener((CompoundButton b, boolean on) -> listener.onChanged(on));
    row.addView(sw);
  }

  private void addActionButton(String label, Runnable action) {
    TextView btn = new TextView(this);
    btn.setText(label);
    btn.setTextColor(Color.parseColor("#35D0DD"));
    btn.setTextSize(14);
    btn.setPadding(dp(12), dp(10), dp(12), dp(10));
    btn.setBackground(cardBg());
    btn.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams lp = matchWrap();
    lp.bottomMargin = dp(6);
    contentHost.addView(btn, lp);
  }

  private GradientDrawable cardBg() {
    GradientDrawable d = new GradientDrawable();
    d.setColor(Color.parseColor("#1A1A1A"));
    d.setCornerRadius(dp(10));
    return d;
  }

  private interface SwitchListener {
    void onChanged(boolean on);
  }

  private LinearLayout.LayoutParams matchWrap() {
    return new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
  }

  private int dp(int v) {
    return Math.round(v * density);
  }
}
