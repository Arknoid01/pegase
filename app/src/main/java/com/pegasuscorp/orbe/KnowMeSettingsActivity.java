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
import com.pegasuscorp.orbe.voice.LearnModeStore;
import com.pegasuscorp.orbe.voice.VoiceIntentLearnStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Corpus « Apprends à me connaître » — formulations confirmées → intentions.
 */
public class KnowMeSettingsActivity extends AppCompatActivity {

    private float density;
    private LinearLayout contentHost;
    private VoiceIntentLearnStore store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = VoiceIntentLearnStore.getInstance(this);
        density = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F00B0E14"));
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText("🎓 Apprends à me connaître");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Ton corpus personnel : ce que tu dis → ce que Pégase fait");
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
        rebuildList();
    }

    private void rebuildList() {
        contentHost.removeAllViews();

        contentHost.addView(hint(
                "Pégase n'enregistre que tes confirmations (oui) et tes corrections.\n"
                        + "Synonymes regroupés · séquences composites · mode professeur à la voix :\n"
                        + "« Apprends que quand je dis on code, c'est ouvrir Orion puis Spotify »"));

        addActionButton(LearnModeStore.isEnabled(this)
                ? "Désactiver le mode apprentissage"
                : "Activer le mode apprentissage", () -> {
            boolean enable = !LearnModeStore.isEnabled(this);
            LearnModeStore.setEnabled(this, enable);
            toast(enable
                    ? "Mode apprentissage activé — Pégase posera plus de questions."
                    : "Mode apprentissage désactivé.");
            rebuildList();
        });

        int weekCount = store.countLearnedThisWeek();
        if (weekCount > 0) {
            contentHost.addView(hint("Cette semaine : " + weekCount + " formulation"
                    + (weekCount > 1 ? "s" : "") + " apprise"
                    + (weekCount > 1 ? "s" : "") + ". Supprime celles qui sont mauvaises."));
        }

        addActionButton("Exporter le corpus (JSON)", this::exportCorpus);
        addActionButton("Tout effacer", this::confirmClearAll);

        List<VoiceIntentLearnStore.LearnedIntent> entries = store.getEntries();
        if (entries.isEmpty()) {
            contentHost.addView(emptyLabel("Aucune formulation apprise pour l'instant.\n"
                    + "Active le mode et confirme quelques actions à la voix."));
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            final int index = i;
            contentHost.addView(intentCard(entries.get(i), index));
        }
    }

    private View intentCard(VoiceIntentLearnStore.LearnedIntent e, int index) {
        LinearLayout card = cardContainer();

        TextView phrase = new TextView(this);
        String title = e.composite ? "⚡ « " + e.utterance + " »" : "✓ « " + e.utterance + " »";
        phrase.setText(title);
        phrase.setTextColor(Color.WHITE);
        phrase.setTextSize(14);
        phrase.setTypeface(null, Typeface.BOLD);
        card.addView(phrase);

        if (!e.label.isEmpty()) {
            TextView label = new TextView(this);
            label.setText("→ " + e.label);
            label.setTextColor(Color.parseColor("#CCFFFFFF"));
            label.setTextSize(13);
            label.setPadding(0, dp(2), 0, dp(2));
            card.addView(label);
        }

        if (!e.synonyms.isEmpty()) {
            TextView syns = new TextView(this);
            syns.setText("Synonymes : " + String.join(" · ", e.synonyms));
            syns.setTextColor(Color.parseColor("#88FFFFFF"));
            syns.setTextSize(11);
            syns.setPadding(0, 0, 0, dp(4));
            card.addView(syns);
        }

        TextView intent = new TextView(this);
        intent.setText(e.intentHint.isEmpty()
                ? e.confirmations + " confirmation" + (e.confirmations > 1 ? "s" : "")
                : e.intentHint + " · " + e.confirmations + " confirmation"
                + (e.confirmations > 1 ? "s" : ""));
        intent.setTextColor(Color.parseColor("#88FFFFFF"));
        intent.setTextSize(11);
        intent.setPadding(0, 0, 0, dp(4));
        card.addView(intent);

        if (e.learnedAtMs > 0) {
            TextView meta = new TextView(this);
            meta.setText("Appris le " + formatDate(e.learnedAtMs)
                    + (e.source.isEmpty() ? "" : " · " + e.source));
            meta.setTextColor(Color.parseColor("#66FFFFFF"));
            meta.setTextSize(11);
            meta.setPadding(0, 0, 0, dp(6));
            card.addView(meta);
        }

        card.addView(smallButton("Supprimer", () -> confirmDelete(index, e.utterance)));
        return card;
    }

    private void confirmDelete(int index, String utterance) {
        new MaterialAlertDialogBuilder(this, R.style.Theme_Orbe_DarkDialog)
                .setTitle("Supprimer cette formulation ?")
                .setMessage("« " + utterance + " » ne sera plus reconnue automatiquement.")
                .setPositiveButton("Supprimer", (d, w) -> {
                    store.removeAt(index);
                    rebuildList();
                    toast("Formulation supprimée.");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void exportCorpus() {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            java.io.File out = new java.io.File(dir, "pegase-corpus-export.json");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(store.exportJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            toast("Exporté : " + out.getAbsolutePath());
        } catch (Exception e) {
            toast("Export impossible.");
        }
    }

    private void confirmClearAll() {
        new MaterialAlertDialogBuilder(this, R.style.Theme_Orbe_DarkDialog)
                .setTitle("Tout effacer ?")
                .setMessage("Tout le corpus personnel sera supprimé.")
                .setPositiveButton("Effacer", (d, w) -> {
                    store.clearAll();
                    rebuildList();
                    toast("Corpus vidé.");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private String formatDate(long ms) {
        return new SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(new Date(ms));
    }

    private void addActionButton(String label, Runnable action) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(14);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#FF2A3140"));
        bg.setCornerRadius(dp(10));
        btn.setBackground(bg);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(8);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> action.run());
        contentHost.addView(btn);
    }

    private TextView hint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.parseColor("#88FFFFFF"));
        t.setTextSize(12);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    private TextView emptyLabel(String text) {
        TextView t = hint(text);
        t.setPadding(dp(8), dp(16), dp(8), dp(16));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView smallButton(String label, Runnable action) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.parseColor("#FF8AB4FF"));
        b.setTextSize(13);
        b.setPadding(0, dp(4), dp(12), 0);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private LinearLayout cardContainer() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#FF1A1D24"));
        bg.setCornerRadius(dp(10));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);
        return card;
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
}
