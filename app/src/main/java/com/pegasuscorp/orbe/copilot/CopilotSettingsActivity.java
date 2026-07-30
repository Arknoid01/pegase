package com.pegasuscorp.orbe.copilot;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.pegasuscorp.orbe.iface.IfaceUi;
import com.pegasuscorp.orbe.R;
import com.pegasuscorp.orbe.notifications.NotificationAccess;
import com.pegasuscorp.orbe.ui.OrbeTokens;

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

  private LinearLayout contentHost;
  private ActivityResultLauncher<Intent> screenPickerLauncher;
  private ActivityResultLauncher<Intent> notifPickerLauncher;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    screenPickerLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), r -> rebuild());
    notifPickerLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), r -> rebuild());

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(OrbeTokens.COLOR_BG);
    root.setPadding(dp(14), dp(14), dp(14), dp(14));

    TextView title = new TextView(this);
    title.setText(getString(R.string.copilot_settings_title));
    title.setTextColor(OrbeTokens.COLOR_TEXT);
    title.setTextSize(20);
    title.setTypeface(OrbeTokens.typeMedium());
    root.addView(title);

    TextView subtitle = new TextView(this);
    subtitle.setText(getString(R.string.copilot_settings_subtitle));
    subtitle.setTextColor(OrbeTokens.COLOR_MUTED_TEXT);
    subtitle.setTextSize(12);
    subtitle.setPadding(0, dp(4), 0, dp(12));
    root.addView(subtitle);

    contentHost = new LinearLayout(this);
    contentHost.setOrientation(LinearLayout.VERTICAL);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(contentHost, IfaceUi.matchWrap());
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
    addSectionTitle(getString(R.string.copilot_section_general));
    addSwitch(getString(R.string.copilot_toggle_always_on), CopilotPrefs.isAlwaysOn(this), (on) -> {
      CopilotPrefs.setAlwaysOn(this, on);
    });
    addSwitch(getString(R.string.copilot_toggle_screen_analysis), CopilotPrefs.isScreenAnalysisEnabled(this), (on) -> {
      CopilotPrefs.setScreenAnalysisEnabled(this, on);
      if (on) CopilotClient.get().sync(this);
    });
    addSwitch(getString(R.string.copilot_toggle_translation), CopilotPrefs.isTranslationOverlayEnabled(this), (on) -> {
      CopilotPrefs.setTranslationOverlayEnabled(this, on);
      if (!on) TranslationOverlayService.hide(this);
    });
    addSwitch(getString(R.string.copilot_toggle_highlight), CopilotPrefs.isElementHighlightEnabled(this), (on) -> {
      CopilotPrefs.setElementHighlightEnabled(this, on);
      if (!on) ElementHighlightService.hide(this);
    });
    addSwitch(getString(R.string.copilot_toggle_notif), CopilotPrefs.isNotificationCopilotEnabled(this), (on) -> {
      CopilotPrefs.setNotificationCopilotEnabled(this, on);
    });

    addSectionTitle(getString(R.string.copilot_section_permissions));
    addPermissionRow(getString(R.string.copilot_perm_overlay),
            Settings.canDrawOverlays(this),
            () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + getPackageName()))));
    addPermissionRow(getString(R.string.copilot_perm_accessibility),
            AccessibilityAccess.isEnabled(this),
            () -> AccessibilityAccess.openSettings(this));
    addPermissionRow(getString(R.string.copilot_perm_notifications),
            NotificationAccess.isEnabled(this),
            () -> NotificationAccess.openSettings(this));
    addPermissionRow(getString(R.string.copilot_perm_capture),
            ScreenCaptureHelper.hasPermission(),
            () -> ScreenCapturePermissionActivity.request(this, granted -> rebuild()));

    addSectionTitle(getString(R.string.copilot_section_apps_screen));
    addHint(getString(R.string.copilot_hint_screen_apps));
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
      screenWl.remove(p.packageName);
    }
    for (String pkg : screenWl) {
      addCustomAppRow(pkg, true);
    }
    addActionButton(getString(R.string.copilot_add_app_screen), () -> {
      Intent i = new Intent(this, CopilotAppPickerActivity.class);
      i.putExtra(CopilotAppPickerActivity.EXTRA_TARGET, CopilotAppPickerActivity.TARGET_SCREEN);
      screenPickerLauncher.launch(i);
    });

    addSectionTitle(getString(R.string.copilot_section_apps_notif));
    addHint(getString(R.string.copilot_hint_notif_apps));
    Set<String> notifWl = new HashSet<>(CopilotPrefs.getNotificationWhitelist(this));
    for (AppPreset p : PRESETS) {
      boolean on = notifWl.contains(p.packageName);
      addSwitch(p.label + getString(R.string.copilot_notif_preset_suffix), on, (checked) -> {
        Set<String> wl = new HashSet<>(CopilotPrefs.getNotificationWhitelist(this));
        if (checked) wl.add(p.packageName);
        else wl.remove(p.packageName);
        CopilotPrefs.setNotificationWhitelist(this, wl);
        if (checked) CopilotPrefs.setNotificationCopilotEnabled(this, true);
      });
      notifWl.remove(p.packageName);
    }
    for (String pkg : notifWl) {
      addCustomAppRow(pkg, false);
    }
    addActionButton(getString(R.string.copilot_add_app_notif), () -> {
      Intent i = new Intent(this, CopilotAppPickerActivity.class);
      i.putExtra(CopilotAppPickerActivity.EXTRA_TARGET, CopilotAppPickerActivity.TARGET_NOTIF);
      notifPickerLauncher.launch(i);
    });
  }

  private void addCustomAppRow(String packageName, boolean screenList) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(10), dp(8), dp(10), dp(8));
    row.setBackground(cardBg());
    LinearLayout.LayoutParams lp = IfaceUi.matchWrap();
    lp.bottomMargin = dp(6);
    contentHost.addView(row, lp);

    TextView label = new TextView(this);
    label.setText(resolveLabel(packageName));
    label.setTextColor(OrbeTokens.COLOR_TEXT);
    label.setTextSize(14);
    row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

    TextView remove = new TextView(this);
    remove.setText(getString(R.string.copilot_remove_app));
    remove.setTextColor(OrbeTokens.COLOR_CYAN);
    remove.setTextSize(13);
    remove.setPadding(dp(8), dp(4), dp(4), dp(4));
    remove.setOnClickListener(v -> {
      if (screenList) CopilotPrefs.removeFromWhitelist(this, packageName);
      else CopilotPrefs.removeFromNotificationWhitelist(this, packageName);
      rebuild();
    });
    row.addView(remove);
  }

  private String resolveLabel(String packageName) {
    try {
      PackageManager pm = getPackageManager();
      CharSequence cs = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0));
      if (cs != null && cs.length() > 0) return cs.toString();
    } catch (Exception ignored) {}
    return packageName;
  }

  private void addSectionTitle(String text) {
    TextView tv = new TextView(this);
    tv.setText(text);
    tv.setTextColor(OrbeTokens.COLOR_CYAN);
    tv.setTextSize(14);
    tv.setTypeface(OrbeTokens.typeMedium());
    tv.setPadding(0, dp(14), 0, dp(6));
    contentHost.addView(tv, IfaceUi.matchWrap());
  }

  private void addHint(String text) {
    TextView tv = new TextView(this);
    tv.setText(text);
    tv.setTextColor(OrbeTokens.COLOR_MUTED_TEXT);
    tv.setTextSize(11);
    tv.setPadding(0, 0, 0, dp(8));
    contentHost.addView(tv, IfaceUi.matchWrap());
  }

  private void addSwitch(String label, boolean checked, SwitchListener listener) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(10), dp(8), dp(10), dp(8));
    row.setBackground(cardBg());
    LinearLayout.LayoutParams lp = IfaceUi.matchWrap();
    lp.bottomMargin = dp(6);
    contentHost.addView(row, lp);

    TextView tv = new TextView(this);
    tv.setText(label);
    tv.setTextColor(OrbeTokens.COLOR_TEXT);
    tv.setTextSize(14);
    row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

    SwitchCompat sw = new SwitchCompat(this);
    sw.setChecked(checked);
    sw.setOnCheckedChangeListener((CompoundButton b, boolean on) -> listener.onChanged(on));
    row.addView(sw);
  }

  private void addPermissionRow(String label, boolean granted, Runnable action) {
    String status = granted
            ? getString(R.string.copilot_perm_granted)
            : getString(R.string.copilot_perm_missing);
    TextView btn = new TextView(this);
    btn.setText(label + status);
    btn.setTextColor(granted ? OrbeTokens.COLOR_CYAN : OrbeTokens.COLOR_MUTED);
    btn.setTextSize(14);
    btn.setPadding(dp(12), dp(10), dp(12), dp(10));
    btn.setBackground(cardBg());
    btn.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams lp = IfaceUi.matchWrap();
    lp.bottomMargin = dp(6);
    contentHost.addView(btn, lp);
  }

  private void addActionButton(String label, Runnable action) {
    TextView btn = new TextView(this);
    btn.setText(label);
    btn.setTextColor(OrbeTokens.COLOR_CYAN);
    btn.setTextSize(14);
    btn.setPadding(dp(12), dp(10), dp(12), dp(10));
    btn.setBackground(cardBg());
    btn.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams lp = IfaceUi.matchWrap();
    lp.bottomMargin = dp(6);
    contentHost.addView(btn, lp);
  }

  private GradientDrawable cardBg() {
    GradientDrawable d = new GradientDrawable();
    d.setColor(OrbeTokens.COLOR_CARD);
    d.setCornerRadius(dp(OrbeTokens.RADIUS_MD));
    return d;
  }

  private interface SwitchListener {
    void onChanged(boolean on);
  }

  private int dp(int v) {
    return IfaceUi.dp(this, v);
  }
}
