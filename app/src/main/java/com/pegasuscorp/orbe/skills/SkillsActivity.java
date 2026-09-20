package com.pegasuscorp.orbe.skills;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.InputStream;
import java.util.List;

/** Skills : liste, activation, aperçu, suppression et import de ZIP. */
public class SkillsActivity extends AppCompatActivity {

    private static final int C_BG = Color.parseColor("#F00B0E14");
    private static final int C_CYAN = Color.parseColor("#35D0DD");
    private static final int C_MUTED = Color.parseColor("#88FFFFFF");

    private final Handler main = new Handler(Looper.getMainLooper());
    private float density;
    private LinearLayout root;
    private LinearLayout list;

    private final ActivityResultLauncher<String[]> zipPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importZip);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Skills");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("Procédures que Pégase lit à la demande (dossier avec un SKILL.md). "
                + "Seul l'index (nom + description) entre dans le prompt ; un skill désactivé "
                + "n'est plus visible du modèle. Les skills viennent de toi : le modèle ne peut "
                + "pas en écrire.");
        help.setTextColor(C_MUTED);
        help.setTextSize(13);
        help.setPadding(0, dp(10), 0, dp(12));
        root.addView(help);

        Button add = button("Importer un ZIP", C_CYAN);
        add.setOnClickListener(v -> zipPicker.launch(new String[]{"application/zip",
                "application/x-zip-compressed", "application/octet-stream"}));
        root.addView(add, wrapLp());

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, wrapLp());

        setContentView(scroll);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(dp(14) + bars.left, dp(14) + bars.top,
                    dp(14) + bars.right, dp(14) + bars.bottom);
            return insets;
        });
        render();
    }

    private void render() {
        list.removeAllViews();
        SkillStore store = new SkillStore(this);
        List<SkillStore.Skill> skills = store.listAll();
        if (skills.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Aucun skill installé. Importe un ZIP contenant <id>/SKILL.md "
                    + "(frontmatter name + description).");
            empty.setTextColor(C_MUTED);
            empty.setPadding(0, dp(12), 0, 0);
            list.addView(empty);
            return;
        }
        for (SkillStore.Skill s : skills) list.addView(card(store, s), wrapLp());
    }

    private LinearLayout card(SkillStore store, SkillStore.Skill skill) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(cardBg());

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(skill.name + "  ·  " + skill.id);
        name.setTextColor(Color.WHITE);
        name.setTextSize(15);
        name.setTypeface(null, Typeface.BOLD);
        head.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch enabled = new Switch(this);
        enabled.setChecked(store.isEnabled(skill.id));
        enabled.setOnCheckedChangeListener((b, checked) -> {
            store.setEnabled(skill.id, checked);
            Toast.makeText(this, checked ? "Skill activé" : "Skill désactivé",
                    Toast.LENGTH_SHORT).show();
        });
        head.addView(enabled);
        card.addView(head);

        TextView desc = new TextView(this);
        desc.setText(skill.description);
        desc.setTextColor(C_MUTED);
        desc.setTextSize(12);
        desc.setPadding(0, dp(4), 0, 0);
        card.addView(desc);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button preview = button("Aperçu", C_CYAN);
        preview.setOnClickListener(v -> showPreview(store, skill));
        Button del = button("Supprimer", C_MUTED);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Supprimer le skill « " + skill.name + " » et ses fichiers ?")
                .setPositiveButton("Supprimer", (d, w) -> {
                    boolean ok = store.delete(skill.id);
                    Toast.makeText(this, ok ? "Skill supprimé" : "Suppression impossible",
                            Toast.LENGTH_SHORT).show();
                    render();
                })
                .setNegativeButton("Annuler", null).show());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(preview, lp);
        row.addView(del, lp);
        card.addView(row);
        return card;
    }

    private void showPreview(SkillStore store, SkillStore.Skill skill) {
        String body;
        try {
            body = store.readBody(skill);
            List<String> res = store.listResources(skill);
            if (!res.isEmpty()) body += "\n\nRessources : " + String.join(", ", res);
        } catch (Exception e) {
            body = "Lecture impossible : " + e.getMessage();
        }
        TextView text = new TextView(this);
        text.setText(body);
        text.setTextSize(13);
        text.setPadding(dp(20), dp(12), dp(20), dp(12));
        text.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(text);
        new AlertDialog.Builder(this).setTitle(skill.name).setView(sv)
                .setPositiveButton("Fermer", null).show();
    }

    private void importZip(@Nullable Uri uri) {
        if (uri == null) return;
        final android.content.Context app = getApplicationContext();
        final String fallbackId = SkillStore.idFromFileName(displayName(uri));
        new Thread(() -> {
            String message;
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("Fichier illisible.");
                List<String> ids = new SkillStore(app).importZip(in, fallbackId);
                message = ids.isEmpty()
                        ? "Aucun skill valide dans cette archive (SKILL.md avec description requis)."
                        : "Skills installés : " + String.join(", ", ids);
            } catch (Exception e) {
                message = "Import impossible : "
                        + (e.getMessage() != null ? e.getMessage() : "erreur");
            }
            final String toast = message;
            main.post(() -> {
                if (isFinishing()) return;
                Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
                render();
            });
        }, "skill-import").start();
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    private Button button(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setBackground(cardBg());
        return b;
    }

    private GradientDrawable cardBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#22FFFFFF"));
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        return bg;
    }

    private LinearLayout.LayoutParams wrapLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
