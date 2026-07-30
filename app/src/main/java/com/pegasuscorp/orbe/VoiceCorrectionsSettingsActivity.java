package com.pegasuscorp.orbe;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
import com.pegasuscorp.orbe.voice.VoiceCorrectionStore;

import java.util.List;

/**
 * Éditeur des corrections vocales apprises (heard → meant) pour le micro.
 */
public class VoiceCorrectionsSettingsActivity extends AppCompatActivity {

    private static final int FIELD_BG = Color.parseColor("#FF252830");

    private float density;
    private LinearLayout contentHost;
    private VoiceCorrectionStore store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = VoiceCorrectionStore.getInstance(this);
        density = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F00B0E14"));
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText("Corrections vocales");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Ce que le micro entend mal → ce que Pégase comprend");
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
                "Quand le micro transcrit mal, Pégase remplace automatiquement avant d'analyser.\n"
                        + "Les corrections s'ajoutent aussi à la voix : « non, je voulais dire… ».\n"
                        + "Intention (optionnel) : météo, sports, recherche…"));

        addActionButton("+ Ajouter une correction", this::showAddDialog);
        addActionButton("Réinitialiser les exemples par défaut", this::confirmResetDefaults);
        addActionButton("Tout effacer", this::confirmClearAll);

        List<VoiceCorrectionStore.CorrectionEntry> entries = store.getEntries();
        if (entries.isEmpty()) {
            contentHost.addView(emptyLabel("Aucune correction enregistrée."));
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            final int index = i;
            contentHost.addView(correctionCard(entries.get(i), index));
        }
    }

    private View correctionCard(VoiceCorrectionStore.CorrectionEntry e, int index) {
        LinearLayout card = cardContainer();

        TextView heard = new TextView(this);
        heard.setText("Entendu : " + e.heard);
        heard.setTextColor(Color.WHITE);
        heard.setTextSize(14);
        heard.setTypeface(null, Typeface.BOLD);
        card.addView(heard);

        TextView meant = new TextView(this);
        meant.setText("→ " + e.meant);
        meant.setTextColor(Color.parseColor("#CCFFFFFF"));
        meant.setTextSize(14);
        meant.setPadding(0, dp(4), 0, dp(4));
        card.addView(meant);

        if (e.intentHint != null && !e.intentHint.isEmpty()) {
            TextView intent = new TextView(this);
            intent.setText("Intention : " + e.intentHint);
            intent.setTextColor(Color.parseColor("#88FFFFFF"));
            intent.setTextSize(11);
            intent.setPadding(0, 0, 0, dp(6));
            card.addView(intent);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(smallButton("Modifier", () -> showEditDialog(index, e)));
        actions.addView(spacerH(dp(8)));
        actions.addView(smallButton("Supprimer", () -> confirmDelete(index, e.heard)));
        card.addView(actions);
        return card;
    }

    private void showAddDialog() {
        showCorrectionDialog("Nouvelle correction", "", "", "",
                (heard, meant, intent) -> {
                    store.learn(heard, meant, intent);
                    rebuildList();
                    toast("Correction ajoutée.");
                });
    }

    private void showEditDialog(int index, VoiceCorrectionStore.CorrectionEntry e) {
        showCorrectionDialog("Modifier la correction", e.heard, e.meant, e.intentHint,
                (heard, meant, intent) -> {
                    store.updateAt(index, heard, meant, intent);
                    rebuildList();
                    toast("Correction mise à jour.");
                });
    }

    private void showCorrectionDialog(String title, String heard, String meant, String intent,
                                      CorrectionSaveCallback onSave) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(8), dp(8), dp(8), dp(8));
        layout.setBackgroundColor(Color.parseColor("#FF1A1D24"));

        EditText heardField = field(heard, "Ce que le micro entend (ex: psg talon)", 1);
        layout.addView(heardField);
        layout.addView(spacerV(dp(8)));
        EditText meantField = field(meant, "Ce que tu voulais dire (ex: PSG foot)", 2);
        layout.addView(meantField);
        layout.addView(spacerV(dp(8)));
        EditText intentField = field(intent, "Intention (optionnel : météo, sports…)", 1);
        layout.addView(intentField);

        darkDialog()
                .setTitle(title)
                .setView(layout)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String h = heardField.getText().toString().trim();
                    String m = meantField.getText().toString().trim();
                    String i = intentField.getText().toString().trim();
                    if (h.isEmpty() || m.isEmpty()) {
                        toast("Les champs « entendu » et « voulu » sont obligatoires.");
                        return;
                    }
                    if (h.equalsIgnoreCase(m)) {
                        toast("Les deux textes doivent être différents.");
                        return;
                    }
                    onSave.onSave(h, m, i);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void confirmDelete(int index, String heard) {
        darkDialog()
                .setTitle("Supprimer cette correction ?")
                .setMessage("« " + heard + " » ne sera plus corrigé automatiquement.")
                .setPositiveButton("Supprimer", (d, w) -> {
                    store.removeAt(index);
                    rebuildList();
                    toast("Correction supprimée.");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void confirmClearAll() {
        darkDialog()
                .setTitle("Tout effacer ?")
                .setMessage("Toutes les corrections apprises seront supprimées.")
                .setPositiveButton("Effacer", (d, w) -> {
                    store.clearAll();
                    rebuildList();
                    toast("Liste vidée.");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void confirmResetDefaults() {
        darkDialog()
                .setTitle("Réinitialiser les exemples ?")
                .setMessage("Remplace la liste par les corrections par défaut (PSG, météo, Pégase…).")
                .setPositiveButton("Réinitialiser", (d, w) -> {
                    store.resetToDefaults();
                    rebuildList();
                    toast("Exemples par défaut restaurés.");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ── UI helpers ───────────────────────────────────────────────────────────

    private MaterialAlertDialogBuilder darkDialog() {
        return new MaterialAlertDialogBuilder(this, R.style.Theme_Orbe_DarkDialog);
    }

    private EditText field(String value, String hint, int minLines) {
        EditText f = new EditText(this);
        f.setText(value);
        f.setHint(hint);
        f.setTextColor(Color.WHITE);
        f.setHintTextColor(Color.parseColor("#88FFFFFF"));
        f.setBackgroundColor(FIELD_BG);
        f.setPadding(dp(12), dp(10), dp(12), dp(10));
        f.setMinLines(minLines);
        f.setGravity(Gravity.TOP);
        f.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        return f;
    }

    private LinearLayout cardContainer() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.parseColor("#18FFFFFF"));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
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

    private TextView emptyLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#55FFFFFF"));
        tv.setTextSize(14);
        tv.setPadding(0, dp(20), 0, dp(20));
        tv.setGravity(Gravity.CENTER);
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

    private TextView smallButton(String label, Runnable action) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.parseColor("#35D0DD"));
        btn.setTextSize(13);
        btn.setPadding(dp(4), dp(4), dp(12), dp(4));
        btn.setOnClickListener(v -> action.run());
        return btn;
    }

    private View spacerH(int w) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(w, 1));
        return v;
    }

    private View spacerV(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
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

    private interface CorrectionSaveCallback {
        void onSave(String heard, String meant, String intent);
    }
}
