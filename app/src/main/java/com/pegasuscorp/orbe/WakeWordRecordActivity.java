package com.pegasuscorp.orbe;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pegasuscorp.orbe.voice.AudioCapture;
import com.pegasuscorp.orbe.voice.VoiceWakeClient;
import com.pegasuscorp.orbe.voice.WakeOwwBackboneDownloader;
import com.pegasuscorp.orbe.voice.WakeOwwStore;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enregistrement guidé « Hey Pégase » pour entraîner openWakeWord
 * (même micro MIC 16 kHz que le wake en production).
 */
public class WakeWordRecordActivity extends AppCompatActivity {

    private static final int TARGET_CLIPS = 40;
    private static final int RECORD_MS = 1800;
    private static final int PREPARE_MS = 2000;
    private static final int PAUSE_MS = 1500;

    private float density;
    private LinearLayout contentHost;
    private TextView statusView;
    private TextView promptView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean sessionRunning = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    private final ActivityResultLauncher<String> micPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startSession();
                else toast("Permission micro requise");
            });

    private final ActivityResultLauncher<String[]> importClassifier =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                io.execute(() -> {
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IllegalStateException("Lecture impossible");
                        WakeOwwStore.importClassifier(this, in);
                        main.post(() -> {
                            toast("hey_pegase.onnx importé — bascule openWakeWord…");
                            rebuild();
                            // Force VoiceService à recharger le backend (sinon reste sur Sherpa).
                            VoiceWakeClient.get().resetKwsCrashGuard(this);
                            VoiceWakeClient.get().sync(this);
                        });
                    } catch (Exception e) {
                        main.post(() -> toast("Import échoué : " + e.getMessage()));
                    }
                });
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F00B0E14"));
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText("Wake word openWakeWord");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Enregistre « Hey Pégase » sur le micro du téléphone, "
                + "exporte les WAV, entraîne sur PC, importe le .onnx.");
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
    protected void onStart() {
        super.onStart();
        VoiceWakeClient.get().pauseKeepSco(this);
    }

    @Override
    protected void onStop() {
        stopRequested.set(true);
        sessionRunning.set(false);
        if (!isChangingConfigurations()) {
            VoiceWakeClient.get().startListening(this);
        }
        super.onStop();
    }

    private void rebuild() {
        contentHost.removeAllViews();
        contentHost.addView(hint(
                "1. Lance une session (~40 clips, ~1,8 s chacun)\n"
                        + "2. Varie ton / rythme / distance au micro\n"
                        + "3. Partage le zip → entraîne sur PC (voir docs/openwakeword.md)\n"
                        + "4. Importe hey_pegase.onnx + télécharge le backbone"));

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(14);
        statusView.setPadding(0, dp(8), 0, dp(8));
        updateStatus();
        contentHost.addView(statusView);

        promptView = new TextView(this);
        promptView.setTextColor(Color.parseColor("#FFD54F"));
        promptView.setTextSize(16);
        promptView.setTypeface(null, Typeface.BOLD);
        promptView.setPadding(0, 0, 0, dp(12));
        promptView.setText(sessionRunning.get() ? "Session en cours…" : "Prêt");
        contentHost.addView(promptView);

        TextView owwStatus = new TextView(this);
        owwStatus.setText(WakeOwwStore.statusLabel(this));
        owwStatus.setTextColor(Color.parseColor("#AAFFFFFF"));
        owwStatus.setTextSize(12);
        owwStatus.setPadding(0, 0, 0, dp(10));
        contentHost.addView(owwStatus);

        if (!sessionRunning.get()) {
            addActionButton("Démarrer l'enregistrement (40 clips)", this::requestMicAndStart);
            if (WakeOwwStore.countSamples(this) > 0) {
                addActionButton("Continuer (+40 clips)", this::requestMicAndStart);
            }
        } else {
            addActionButton("Arrêter la session", () -> {
                stopRequested.set(true);
                toast("Arrêt après le clip en cours…");
            });
        }

        addActionButton("Partager les échantillons (zip)", this::shareSamples);
        addActionButton("Importer hey_pegase.onnx", () ->
                importClassifier.launch(new String[]{"application/octet-stream", "*/*"}));
        addActionButton(WakeOwwStore.isBackboneReady(this)
                        ? "Réinstaller backbone openWakeWord"
                        : "Télécharger backbone openWakeWord (~1,5 Mo)",
                this::downloadBackbone);
        addActionButton("Effacer les échantillons", this::confirmClearSamples);
    }

    private void updateStatus() {
        int n = WakeOwwStore.countSamples(this);
        statusView.setText("Clips enregistrés : " + n
                + (n >= TARGET_CLIPS ? " ✓" : " / objectif " + TARGET_CLIPS));
    }

    private void requestMicAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        startSession();
    }

    private void startSession() {
        if (!sessionRunning.compareAndSet(false, true)) return;
        stopRequested.set(false);
        VoiceWakeClient.get().pauseKeepSco(this);
        rebuild();
        io.execute(this::runRecordingSession);
    }

    private void runRecordingSession() {
        int startIndex = WakeOwwStore.nextSampleIndex(this);
        try {
            for (int i = 0; i < TARGET_CLIPS; i++) {
                if (stopRequested.get() || isFinishing()) break;
                final int clipNum = i + 1;
                final int fileIndex = startIndex + i;

                countdownUi(PREPARE_MS, "Prépare-toi… clip " + clipNum + "/" + TARGET_CLIPS);
                if (stopRequested.get()) break;

                setPrompt("PARLE : Hey Pégase");
                short[] pcm = AudioCapture.recordWakeSamplesMs(RECORD_MS);
                if (pcm.length == 0) {
                    main.post(() -> toast("Échec micro — réessaie"));
                    break;
                }
                File dest = WakeOwwStore.sampleFile(this, fileIndex);
                AudioCapture.writeWav(dest, pcm);
                main.post(() -> {
                    updateStatus();
                    setPrompt("Enregistré " + dest.getName());
                });

                if (i < TARGET_CLIPS - 1 && !stopRequested.get()) {
                    countdownUi(PAUSE_MS, "Pause…");
                }
            }
        } catch (Exception e) {
            main.post(() -> toast("Erreur : " + e.getMessage()));
        } finally {
            sessionRunning.set(false);
            main.post(() -> {
                setPrompt("Session terminée");
                rebuild();
                toast("Total : " + WakeOwwStore.countSamples(this) + " clip(s)");
            });
        }
    }

    private void countdownUi(int durationMs, String label) throws InterruptedException {
        long end = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < end) {
            if (stopRequested.get()) return;
            float rem = Math.max(0f, (end - System.currentTimeMillis()) / 1000f);
            String text = String.format(java.util.Locale.US, "%s %.1fs", label, rem);
            main.post(() -> setPrompt(text));
            Thread.sleep(50);
        }
    }

    private void setPrompt(String text) {
        if (promptView != null) promptView.setText(text);
    }

    private void shareSamples() {
        io.execute(() -> {
            try {
                File zip = WakeOwwStore.zipSamples(this);
                Uri uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", zip);
                Intent share = new Intent(Intent.ACTION_SEND)
                        .setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                main.post(() -> startActivity(Intent.createChooser(share,
                        "Exporter échantillons wake")));
            } catch (Exception e) {
                main.post(() -> toast(e.getMessage() != null ? e.getMessage() : "Export échoué"));
            }
        });
    }

    private void downloadBackbone() {
        if (WakeOwwBackboneDownloader.isDownloading()) {
            toast("Téléchargement déjà en cours…");
            return;
        }
        toast("Téléchargement backbone…");
        WakeOwwBackboneDownloader.download(this, (ok, msg) ->
                main.post(() -> {
                    toast(msg);
                    rebuild();
                    if (ok) VoiceWakeClient.get().sync(this);
                }));
    }

    private void confirmClearSamples() {
        new MaterialAlertDialogBuilder(this, R.style.Theme_Orbe_DarkDialog)
                .setTitle("Effacer les clips ?")
                .setMessage("Supprime tous les WAV sous files/wake_oww/samples/.")
                .setPositiveButton("Effacer", (d, w) -> {
                    WakeOwwStore.clearSamples(this);
                    updateStatus();
                    rebuild();
                    toast("Échantillons effacés");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private TextView hint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#66FFFFFF"));
        tv.setTextSize(11);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(10);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void addActionButton(String label, Runnable action) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(15);
        btn.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.parseColor("#14FFFFFF"));
        btn.setBackground(bg);
        btn.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(10);
        contentHost.addView(btn, lp);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * density);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected void onDestroy() {
        stopRequested.set(true);
        io.shutdownNow();
        super.onDestroy();
    }
}
