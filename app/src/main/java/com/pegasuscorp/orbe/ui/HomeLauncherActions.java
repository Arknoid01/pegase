package com.pegasuscorp.orbe.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.AlarmClock;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.pegasuscorp.orbe.AppPickerActivity;
import com.pegasuscorp.orbe.ShortcutStore;
import com.pegasuscorp.orbe.voice.VoiceInputHandler;

/**
 * Actions launcher HOME : slots raccourcis, apps, téléphone, minuteur, fond système.
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

    public void pickAppForSlot(int slot) {
        AppCompatActivity activity = host.activity();
        pickingShortcutSlot = slot;
        Intent intent = new Intent(activity, AppPickerActivity.class);
        intent.putExtra(AppPickerActivity.EXTRA_SLOT_INDEX, slot);
        shortcutPickerLauncher.launch(intent);
    }

    public void launchPackage(String packageName) {
        AppCompatActivity activity = host.activity();
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch != null) activity.startActivity(launch);
        else Toast.makeText(activity, "Application introuvable", Toast.LENGTH_SHORT).show();
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
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (i.resolveActivity(activity.getPackageManager()) != null) {
            activity.startActivity(i);
            VoiceInputHandler voiceInput = host.voiceInput();
            if (voiceInput != null) voiceInput.speakTimerStarted();
        } else {
            Toast.makeText(activity, "Aucune app de minuteur", Toast.LENGTH_SHORT).show();
        }
    }

    public void launchAppByLabel(String label) {
        if (label == null) return;
        AppCompatActivity activity = host.activity();
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
