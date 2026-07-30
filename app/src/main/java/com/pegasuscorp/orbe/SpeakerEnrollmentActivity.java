package com.pegasuscorp.orbe;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pegasuscorp.orbe.R;
import com.pegasuscorp.orbe.voice.AudioCapture;
import com.pegasuscorp.orbe.voice.SpeakerModelDownloader;
import com.pegasuscorp.orbe.voice.SpeakerModelStore;
import com.pegasuscorp.orbe.voice.SpeakerProfileStore;
import com.pegasuscorp.orbe.voice.SpeakerVerifierEngine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Enregistre l'empreinte vocale de l'utilisateur (3× « Pégase »).
 */
public class SpeakerEnrollmentActivity extends AppCompatActivity {

    private static final int TARGET_SAMPLES = 3;

    private float density;
    private LinearLayout contentHost;
    private TextView statusView;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F00B0E14"));
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText("Ma voix pour Pégase");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Seul toi pourras activer Pégase par le mot d'éveil");
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

    private void rebuild() {
        contentHost.removeAllViews();
        contentHost.addView(hint(
                "1. Télécharge le modèle locuteur (~30 Mo)\n"
                        + "2. Enregistre ta voix en disant « Pégase » "
                        + TARGET_SAMPLES + " fois\n"
                        + "3. Active « Répondre qu'à moi » dans les paramètres"));

        TextView modelStatus = new TextView(this);
        modelStatus.setText(SpeakerModelStore.statusLabel(this));
        modelStatus.setTextColor(Color.parseColor("#AAFFFFFF"));
        modelStatus.setTextSize(12);
        modelStatus.setPadding(0, 0, 0, dp(8));
        contentHost.addView(modelStatus);

        if (!SpeakerModelStore.isModelReady(this)) {
            addActionButton("Télécharger le modèle locuteur", this::downloadModel);
        }

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(14);
        statusView.setPadding(0, dp(8), 0, dp(8));
        updateStatus();
        contentHost.addView(statusView);

        addActionButton("Enregistrer « Pégase » (2 sec)", this::recordSample);
        addActionButton("Réinitialiser mon empreinte", this::confirmClear);
        addActionButton(SpeakerProfileStore.getInstance(this).isRequireOwnerVoice()
                ? "Désactiver « Répondre qu'à moi »"
                : "Activer « Répondre qu'à moi »", () -> {
            SpeakerProfileStore store = SpeakerProfileStore.getInstance(this);
            if (!store.isRequireOwnerVoice() && store.getSampleCount() < TARGET_SAMPLES) {
                toast("Enregistre au moins " + TARGET_SAMPLES + " échantillons d'abord");
                return;
            }
            store.setRequireOwnerVoice(!store.isRequireOwnerVoice());
            toast(store.isRequireOwnerVoice()
                    ? "Vérification vocale activée"
                    : "Vérification vocale désactivée");
            rebuild();
        });
    }

    private void updateStatus() {
        int n = SpeakerProfileStore.getInstance(this).getSampleCount();
        statusView.setText(n == 0
                ? "Aucun échantillon enregistré"
                : "Échantillons : " + n + " / " + TARGET_SAMPLES
                        + (n >= TARGET_SAMPLES ? " ✓" : ""));
    }

    private void downloadModel() {
        toast("Téléchargement en cours…");
        SpeakerModelDownloader.download(this, new SpeakerModelDownloader.Callback() {
            @Override
            public void onProgress(int percent) {
                runOnUiThread(() -> toast("Téléchargement " + percent + "%"));
            }

            @Override
            public void onComplete(boolean success, String message) {
                runOnUiThread(() -> {
                    toast(message);
                    rebuild();
                });
            }
        });
    }

    private void recordSample() {
        if (!SpeakerModelStore.isModelReady(this)) {
            toast("Télécharge d'abord le modèle locuteur");
            return;
        }
        toast("Parle maintenant : Pégase");
        io.execute(() -> {
            float[] samples = AudioCapture.recordSeconds(2);
            SpeakerVerifierEngine.getInstance().enrollSamples(
                    this, samples, new SpeakerVerifierEngine.EnrollCallback() {
                        @Override
                        public void onSuccess(int sampleCount) {
                            runOnUiThread(() -> {
                                updateStatus();
                                toast("Échantillon " + sampleCount + " enregistré");
                                if (sampleCount >= TARGET_SAMPLES) {
                                    toast("Empreinte complète ✓");
                                }
                            });
                        }

                        @Override
                        public void onError(String message) {
                            runOnUiThread(() -> toast(message));
                        }
                    });
        });
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(this, R.style.Theme_Orbe_DarkDialog)
                .setTitle("Effacer l'empreinte ?")
                .setMessage("Pégase ne filtrera plus par voix jusqu'à un nouvel enregistrement.")
                .setPositiveButton("Effacer", (d, w) -> {
                    SpeakerProfileStore.getInstance(this).clear();
                    SpeakerVerifierEngine.getInstance().reloadProfile(this);
                    updateStatus();
                    toast("Empreinte effacée");
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
        io.shutdownNow();
        super.onDestroy();
    }
}
