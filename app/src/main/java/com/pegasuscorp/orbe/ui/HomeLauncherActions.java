package com.pegasuscorp.orbe.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.provider.AlarmClock;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pegasuscorp.orbe.AppPickerActivity;
import com.pegasuscorp.orbe.R;
import com.pegasuscorp.orbe.ShortcutStore;
import com.pegasuscorp.orbe.tools.device.PegaseTimerReceiver;
import com.pegasuscorp.orbe.tools.device.PegaseTimerScheduler;
import com.pegasuscorp.orbe.tools.device.UtilityScheduleStore;
import com.pegasuscorp.orbe.voice.VoiceInputHandler;

/**
 * Actions launcher HOME : slots raccourcis (app ou lien web), apps, téléphone, minuteur.
 */
public final class HomeLauncherActions {

    public interface Host {
        AppCompatActivity activity();
        OrbUiController orbUi();
        VoiceInputHandler voiceInput();
    }

    private final Host host;
    private int pickingShortcutSlot = -1;
    private ActivityResultLauncher<Intent> shortcutPickerLauncher;

    public HomeLauncherActions(Host host) {
        this.host = host;
    }

    public void registerLaunchers(AppCompatActivity activity) {
        shortcutPickerLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == AppCompatActivity.RESULT_OK
                            && result.getData() != null) {
                        String pkg = result.getData().getStringExtra(AppPickerActivity.EXTRA_PACKAGE);
                        if (pickingShortcutSlot >= 0 && pkg != null) {
                            ShortcutStore.setPackage(activity, pickingShortcutSlot, pkg);
                            OrbUiController orbUi = host.orbUi();
                            if (orbUi != null) orbUi.refreshShortcutSlots();
                        }
                    }
                    pickingShortcutSlot = -1;
                });
    }

    /**
     * Menu assignation : app, lien web, ou supprimer.
     * Appui long sur un raccourci déjà placé → « Supprimer » apparaît.
     */
    public void pickAppForSlot(int slot) {
        AppCompatActivity activity = host.activity();
        ShortcutStore.Slot current = ShortcutStore.getSlot(activity, slot);
        if (current.isEmpty()) {
            new MaterialAlertDialogBuilder(activity, R.style.Theme_Orbe_DarkDialog)
                    .setTitle("Nouveau raccourci")
                    .setItems(new CharSequence[]{"Application", "Lien web"}, (d, which) -> {
                        if (which == 0) launchAppPicker(slot);
                        else showWebShortcutDialog(slot, current);
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
            return;
        }

        String title = current.isWeb()
                ? "Raccourci « " + current.label + " »"
                : "Raccourci app";
        if (current.isApp()) {
            try {
                CharSequence label = activity.getPackageManager()
                        .getApplicationLabel(activity.getPackageManager()
                                .getApplicationInfo(current.packageName, 0));
                if (label != null && label.length() > 0) {
                    title = "Raccourci « " + label + " »";
                }
            } catch (Exception ignored) {
            }
        }

        new MaterialAlertDialogBuilder(activity, R.style.Theme_Orbe_DarkDialog)
                .setTitle(title)
                .setItems(new CharSequence[]{
                        "Changer l'application",
                        "Remplacer par un lien web",
                        "Supprimer ce raccourci"
                }, (d, which) -> {
                    if (which == 0) {
                        launchAppPicker(slot);
                    } else if (which == 1) {
                        showWebShortcutDialog(slot, current);
                    } else {
                        ShortcutStore.clearSlot(activity, slot);
                        OrbUiController orbUi = host.orbUi();
                        if (orbUi != null) orbUi.refreshShortcutSlots();
                        Toast.makeText(activity, "Raccourci supprimé", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void launchAppPicker(int slot) {
        AppCompatActivity activity = host.activity();
        pickingShortcutSlot = slot;
        Intent intent = new Intent(activity, AppPickerActivity.class);
        intent.putExtra(AppPickerActivity.EXTRA_SLOT_INDEX, slot);
        shortcutPickerLauncher.launch(intent);
    }

    private void showWebShortcutDialog(int slot, ShortcutStore.Slot current) {
        AppCompatActivity activity = host.activity();
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * density);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText labelInput = new EditText(activity);
        labelInput.setHint("Libellé (ex. Cursor)");
        labelInput.setTextColor(Color.WHITE);
        labelInput.setHintTextColor(Color.parseColor("#88FFFFFF"));
        labelInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        labelInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        if (current != null && current.isWeb()) {
            labelInput.setText(current.label);
        }
        box.addView(labelInput);

        EditText urlInput = new EditText(activity);
        urlInput.setHint("Adresse web (https://…)");
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHintTextColor(Color.parseColor("#88FFFFFF"));
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        urlLp.topMargin = Math.round(12 * density);
        urlInput.setLayoutParams(urlLp);
        if (current != null && current.isWeb()) {
            urlInput.setText(current.url);
        }
        box.addView(urlInput);

        new MaterialAlertDialogBuilder(activity, R.style.Theme_Orbe_DarkDialog)
                .setTitle("Lien web")
                .setMessage("Dis « ouvre » + le libellé pour ouvrir la page.")
                .setView(box)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String label = labelInput.getText() != null
                            ? labelInput.getText().toString().trim() : "";
                    String url = urlInput.getText() != null
                            ? urlInput.getText().toString().trim() : "";
                    if (label.isEmpty()) {
                        Toast.makeText(activity, "Indique un libellé", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!ShortcutStore.isValidHttpUrl(url)) {
                        Toast.makeText(activity, "URL invalide", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ShortcutStore.setWeb(activity, slot, label, url);
                    OrbUiController orbUi = host.orbUi();
                    if (orbUi != null) orbUi.refreshShortcutSlots();
                    Toast.makeText(activity, "Raccourci « " + label + " » prêt",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    public void launchSlot(int slot) {
        AppCompatActivity activity = host.activity();
        ShortcutStore.Slot s = ShortcutStore.getSlot(activity, slot);
        if (s.isEmpty()) {
            pickAppForSlot(slot);
            return;
        }
        if (s.isWeb()) {
            launchUrl(s.url, s.label);
            return;
        }
        launchPackage(s.packageName);
    }

    public void launchPackage(String packageName) {
        AppCompatActivity activity = host.activity();
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch != null) activity.startActivity(launch);
        else Toast.makeText(activity, "Application introuvable", Toast.LENGTH_SHORT).show();
    }

    public void launchUrl(String url, String labelForToast) {
        AppCompatActivity activity = host.activity();
        String normalized = ShortcutStore.normalizeUrl(url);
        if (!ShortcutStore.isValidHttpUrl(normalized)) {
            Toast.makeText(activity, "Lien invalide", Toast.LENGTH_SHORT).show();
            return;
        }
        int hash = normalized.indexOf('#');
        String base = hash >= 0 ? normalized.substring(0, hash) : normalized;
        String openUrl = base + "#pegase=" + System.currentTimeMillis();
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(openUrl));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            activity.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(activity, "Impossible d'ouvrir le lien"
                            + (labelForToast != null ? " « " + labelForToast + " »" : ""),
                    Toast.LENGTH_SHORT).show();
        }
    }

    public void launchDialer() {
        host.activity().startActivity(new Intent(Intent.ACTION_DIAL));
    }

    public void pickWallpaper() {
        host.activity().startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SET_WALLPAPER), "Choisir le fond d'écran"));
    }

    public void startTimer(int seconds) {
        AppCompatActivity activity = host.activity();
        PegaseTimerScheduler.cancel(activity);
        long fireAt = PegaseTimerScheduler.schedule(activity, seconds, null);
        if (fireAt <= 0L) {
            Toast.makeText(activity, "Impossible de planifier le minuteur", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!PegaseTimerReceiver.isKeyguardLocked(activity)) {
            Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            try {
                if (i.resolveActivity(activity.getPackageManager()) != null) {
                    activity.startActivity(i);
                }
            } catch (Exception ignored) {}
        }
        UtilityScheduleStore.get(activity).recordTimer(seconds, null, System.currentTimeMillis());
        VoiceInputHandler voiceInput = host.voiceInput();
        if (voiceInput != null) voiceInput.speakTimerStarted();
        else Toast.makeText(activity, "Minuteur lancé", Toast.LENGTH_SHORT).show();
    }

    public void launchAppByLabel(String label) {
        if (label == null) return;
        AppCompatActivity activity = host.activity();

        ShortcutStore.Slot web = ShortcutStore.findWebByLabel(activity, label);
        if (web != null) {
            launchUrl(web.url, web.label);
            return;
        }

        PackageManager pm = activity.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String appName = ri.loadLabel(pm).toString();
            if (appName.toLowerCase().contains(label.toLowerCase())) {
                Intent launch = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                if (launch != null) {
                    activity.startActivity(launch);
                    return;
                }
            }
        }
        VoiceInputHandler voiceInput = host.voiceInput();
        if (voiceInput != null) voiceInput.speakAppNotFound(label);
    }
}
