package com.pegasuscorp.orbe;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pegasuscorp.orbe.routing.PhraseCandidate;
import com.pegasuscorp.orbe.routing.UserExamplesStore;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Import conversation → valider phrase→outil → examples.json + VectorStore.
 * Obsolescence voulue ~30 j une fois le routing naturel.
 */
public class RoutingLearningActivity extends AppCompatActivity {

    private enum Mode { STATS, IMPORT, VALIDATE }

    private float density;
    private LinearLayout contentHost;
    private UserExamplesStore store;
    private Mode mode = Mode.STATS;
    private final List<PhraseCandidate> pending = new ArrayList<>();
    private ActivityResultLauncher<String[]> openTxtLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = UserExamplesStore.getInstance(this);
        density = getResources().getDisplayMetrics().density;

        openTxtLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::onTxtPicked);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F00B0E14"));
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText("Apprentissage du routing");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Tes formulations → outil. Matching sémantique avant les règles.");
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
        switch (mode) {
            case IMPORT:
                buildImport();
                break;
            case VALIDATE:
                buildValidate();
                break;
            case STATS:
            default:
                buildStats();
                break;
        }
    }

    private void buildStats() {
        contentHost.addView(hint(
                "Importe une conversation exportée (.txt), valide phrase → outil.\n"
                        + "Seul le pattern normalisé est gardé — pas le contenu complet."));

        int total = store.size();
        TextView totalTv = new TextView(this);
        totalTv.setText("Total : " + total + " exemple" + (total != 1 ? "s" : ""));
        totalTv.setTextColor(Color.WHITE);
        totalTv.setTextSize(16);
        totalTv.setTypeface(null, Typeface.BOLD);
        totalTv.setPadding(0, 0, 0, dp(8));
        contentHost.addView(totalTv);

        Map<String, Integer> counts = store.countsByTool();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() == null || e.getValue() == 0) continue;
            TextView row = new TextView(this);
            row.setText(e.getKey() + "  ·  " + e.getValue()
                    + " phrase" + (e.getValue() > 1 ? "s" : ""));
            row.setTextColor(Color.parseColor("#CCFFFFFF"));
            row.setTextSize(13);
            row.setPadding(0, dp(2), 0, dp(2));
            contentHost.addView(row);
        }

        long last = store.getLastImportAtMs();
        if (last > 0) {
            contentHost.addView(hint("Dernier import : " + formatDate(last)));
        }

        contentHost.addView(spacer(12));
        addActionButton("Importer une conversation", () -> {
            mode = Mode.IMPORT;
            rebuild();
        });
        addActionButton("Exporter examples.json", this::exportExamples);
        addActionButton("Réinitialiser", this::confirmClearAll);

        if (total > 0) {
            contentHost.addView(spacer(8));
            contentHost.addView(hint("Exemples enregistrés (tap pour supprimer) :"));
            for (UserExamplesStore.UserExample ex : store.listExamples()) {
                contentHost.addView(exampleRow(ex));
            }
        }
    }

    private View exampleRow(UserExamplesStore.UserExample ex) {
        LinearLayout card = cardContainer();
        TextView phrase = new TextView(this);
        phrase.setText("« " + ex.phrase + " »");
        phrase.setTextColor(Color.WHITE);
        phrase.setTextSize(13);
        phrase.setTypeface(null, Typeface.BOLD);
        card.addView(phrase);

        TextView tool = new TextView(this);
        tool.setText("→ " + ex.tool);
        tool.setTextColor(Color.parseColor("#88FFFFFF"));
        tool.setTextSize(12);
        tool.setPadding(0, dp(2), 0, dp(4));
        card.addView(tool);

        card.addView(smallButton("Supprimer", () -> {
            store.removeContaining(ex.phrase);
            toast("Exemple supprimé.");
            rebuild();
        }));
        return card;
    }

    private void buildImport() {
        contentHost.addView(hint("Fichier .txt exporté, ou colle le texte ci-dessous."));

        addActionButton("Choisir un fichier .txt", () ->
                openTxtLauncher.launch(new String[]{"text/plain", "text/*", "*/*"}));

        EditText paste = new EditText(this);
        paste.setHint("Yannick : Tu as eut des problèmes ?\nPégase : …");
        paste.setHintTextColor(Color.parseColor("#55FFFFFF"));
        paste.setTextColor(Color.WHITE);
        paste.setTextSize(13);
        paste.setMinLines(8);
        paste.setGravity(Gravity.TOP | Gravity.START);
        paste.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#FF1A1D24"));
        bg.setCornerRadius(dp(10));
        paste.setBackground(bg);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(8);
        paste.setLayoutParams(lp);
        paste.setTag("paste");
        contentHost.addView(paste);

        addActionButton("Extraire les phrases", () -> {
            String text = paste.getText() != null ? paste.getText().toString() : "";
            extractAndValidate(text);
        });
        addActionButton("Retour", () -> {
            mode = Mode.STATS;
            rebuild();
        });
    }

    private void buildValidate() {
        contentHost.addView(hint(pending.size() + " phrase"
                + (pending.size() != 1 ? "s" : "")
                + " — valide l'outil pour chacune."));

        for (int i = 0; i < pending.size(); i++) {
            contentHost.addView(candidateCard(pending.get(i), i));
        }

        contentHost.addView(spacer(8));
        addActionButton("Valider tout (hints)", () -> {
            for (PhraseCandidate c : pending) c.accepted = true;
            rebuild();
            toast("Toutes acceptées — enregistre pour sauver.");
        });
        addActionButton("Enregistrer les acceptées", this::saveAccepted);
        addActionButton("Annuler", () -> {
            pending.clear();
            mode = Mode.STATS;
            rebuild();
        });
    }

    private View candidateCard(PhraseCandidate c, int index) {
        LinearLayout card = cardContainer();

        TextView phrase = new TextView(this);
        phrase.setText("« " + c.phrase + " »");
        phrase.setTextColor(Color.WHITE);
        phrase.setTextSize(14);
        phrase.setTypeface(null, Typeface.BOLD);
        card.addView(phrase);

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                UserExamplesStore.TOOL_OPTIONS);
        spinner.setAdapter(adapter);
        int sel = indexOfTool(c.toolHint);
        if (sel >= 0) spinner.setSelection(sel);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                    int position, long id) {
                c.toolHint = UserExamplesStore.TOOL_OPTIONS[position];
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        LinearLayout.LayoutParams spinLp = matchWrap();
        spinLp.topMargin = dp(6);
        spinLp.bottomMargin = dp(6);
        spinner.setLayoutParams(spinLp);
        card.addView(spinner);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);

        TextView accept = smallButton(c.accepted ? "Accepté" : "Accepter", () -> {
            c.accepted = true;
            rebuild();
        });
        if (c.accepted) accept.setTextColor(Color.parseColor("#FF7DFFB2"));
        actions.addView(accept);

        TextView reject = smallButton(c.accepted ? "Ignorer" : "Ignoré", () -> {
            c.accepted = false;
            rebuild();
        });
        if (!c.accepted) reject.setTextColor(Color.parseColor("#FFFF8A8A"));
        actions.addView(reject);

        card.addView(actions);
        return card;
    }

    private void saveAccepted() {
        int n = 0;
        for (PhraseCandidate c : pending) {
            if (c.accepted) n++;
        }
        if (n == 0) {
            toast("Aucune phrase acceptée.");
            return;
        }
        store.addAll(pending);
        pending.clear();
        mode = Mode.STATS;
        rebuild();
        toast(n + " exemple" + (n > 1 ? "s" : "") + " enregistré"
                + (n > 1 ? "s" : "") + ".");
    }

    private void onTxtPicked(Uri uri) {
        if (uri == null) return;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                toast("Impossible de lire le fichier.");
                return;
            }
            byte[] chunk = new byte[8192];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(chunk)) >= 0) {
                bos.write(chunk, 0, n);
                if (bos.size() > 2_000_000) break;
            }
            extractAndValidate(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            toast("Lecture impossible.");
        }
    }

    private void extractAndValidate(String text) {
        List<PhraseCandidate> found = store.importFromConversation(text);
        if (found.isEmpty()) {
            toast("Aucune phrase utilisateur trouvée.");
            return;
        }
        pending.clear();
        pending.addAll(found);
        mode = Mode.VALIDATE;
        rebuild();
    }

    private void exportExamples() {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            java.io.File out = new java.io.File(dir, "routing-examples-export.json");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(store.exportJson().getBytes(StandardCharsets.UTF_8));
            }
            toast("Exporté : " + out.getAbsolutePath());
        } catch (Exception e) {
            toast("Export impossible.");
        }
    }

    private void confirmClearAll() {
        new MaterialAlertDialogBuilder(this, R.style.Theme_Orbe_DarkDialog)
                .setTitle("Réinitialiser les exemples ?")
                .setMessage("Tous les patterns phrase → outil seront effacés.")
                .setPositiveButton("Effacer", (d, w) -> {
                    store.clearAll();
                    rebuild();
                    toast("Exemples vidés.");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private static int indexOfTool(String tool) {
        String t = UserExamplesStore.normalizeToolId(tool);
        for (int i = 0; i < UserExamplesStore.TOOL_OPTIONS.length; i++) {
            if (UserExamplesStore.TOOL_OPTIONS[i].equals(t)) return i;
        }
        return 0;
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

    private View spacer(int dpH) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(dpH)));
        return v;
    }

    private String formatDate(long ms) {
        return new SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(new Date(ms));
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
