package com.pegasuscorp.orbe.sync;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Choix du dossier Google Drive pour sync bloc-notes (SAF, sans API Drive).
 */
public class DriveSyncSettingsActivity extends AppCompatActivity {

    private static final int REQ_FOLDER = 4401;

    private static final int C_BG = Color.parseColor("#F00B0E14");
    private static final int C_CYAN = Color.parseColor("#35D0DD");
    private static final int C_MUTED = Color.parseColor("#88FFFFFF");

    private float density;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Sync bloc-notes · Google Drive");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText(
                "Choisis le dossier " + DriveSyncLayout.DRIVE_FOLDER_NAME
                + " sur Google Drive (ex. racine/" + DriveSyncLayout.DRIVE_FOLDER_NAME + ").\n\n"
                + "Le téléphone et le PC doivent utiliser le même dossier "
                + "(~/GoogleDrive/" + DriveSyncLayout.DRIVE_FOLDER_NAME + " côté PC).\n\n"
                + "Import PC→tel : l'app lit " + SyncPeers.PC + "/.\n"
                + "Import tel→PC : script systemd lit android-*/.\n\n"
                + "Pas d'API Google — accès direct via le dossier Drive.");
        help.setTextColor(C_MUTED);
        help.setTextSize(13);
        help.setPadding(0, dp(10), 0, dp(12));
        root.addView(help);

        statusView = new TextView(this);
        statusView.setTextColor(C_CYAN);
        statusView.setTextSize(12);
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusView.setBackground(cardBg());
        root.addView(statusView, wrapLp());

        Button pick = new Button(this);
        pick.setText("Choisir le dossier Drive");
        pick.setAllCaps(false);
        pick.setTextColor(C_CYAN);
        pick.setBackground(cardBg());
        pick.setOnClickListener(v -> pickFolder());
        root.addView(pick, wrapLp());

        Button syncNow = new Button(this);
        syncNow.setText("Synchroniser maintenant");
        syncNow.setAllCaps(false);
        syncNow.setTextColor(Color.WHITE);
        syncNow.setBackground(cardBg());
        syncNow.setOnClickListener(v -> syncNow());
        root.addView(syncNow, wrapLp());

        Button clear = new Button(this);
        clear.setText("Oublier le dossier");
        clear.setAllCaps(false);
        clear.setTextColor(C_MUTED);
        clear.setBackground(cardBg());
        clear.setOnClickListener(v -> {
            DriveSyncPrefs.clear(this);
            refreshStatus();
            Toast.makeText(this, "Dossier Drive oublié", Toast.LENGTH_SHORT).show();
        });
        root.addView(clear, wrapLp());

        setContentView(scroll);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(dp(14) + bars.left, dp(14) + bars.top,
                    dp(14) + bars.right, dp(14) + bars.bottom);
            return insets;
        });
        refreshStatus();
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_FOLDER);
    }

    private void syncNow() {
        if (!DriveSyncPrefs.isConfigured(this)) {
            Toast.makeText(this, "Choisis d'abord un dossier Drive", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Sync en cours…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            SyncEngine.Result result = SyncEngine.runOnce(getApplicationContext());
            runOnUiThread(() -> {
                String msg = result.success
                        ? "Sync OK — " + result.sentOps + " exportée(s), "
                        + result.appliedOps + " reçue(s)"
                        : "Sync échouée ou dossier inaccessible";
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                refreshStatus();
            });
        }, "drive-sync-manual").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FOLDER || resultCode != RESULT_OK || data == null) return;
        Uri tree = data.getData();
        if (tree == null) return;
        final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(tree, flags);
        } catch (Exception e) {
            Toast.makeText(this, "Permission Drive refusée", Toast.LENGTH_LONG).show();
            return;
        }
        DriveSyncPrefs.setFolderUri(this, tree);
        refreshStatus();
        Toast.makeText(this, "Dossier Drive enregistré", Toast.LENGTH_SHORT).show();
        DriveSyncEngine.scheduleAfterChange(this);
    }

    private void refreshStatus() {
        Uri uri = DriveSyncPrefs.getFolderUri(this);
        if (uri == null) {
            statusView.setText("État : dossier non configuré");
            return;
        }
        String device = SyncDevice.get(this).deviceId;
        long exported = DriveSyncStateStore.get(this).getLastExportedSeq();
        statusView.setText("État : configuré\n"
                + "URI : " + uri + "\n"
                + "Appareil : " + device + "\n"
                + "Dernière op exportée : seq " + exported);
    }

    private GradientDrawable cardBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#18FFFFFF"));
        g.setCornerRadius(dp(8));
        return g;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private LinearLayout.LayoutParams wrapLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        return lp;
    }
}
