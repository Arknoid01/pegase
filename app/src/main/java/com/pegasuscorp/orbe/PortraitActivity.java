package com.pegasuscorp.orbe;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.pegasuscorp.orbe.memory.Entity;
import com.pegasuscorp.orbe.memory.EntityStore;
import com.pegasuscorp.orbe.memory.MemoryEntry;
import com.pegasuscorp.orbe.memory.MemoryRepository;
import com.pegasuscorp.orbe.memory.SessionSummary;
import com.pegasuscorp.orbe.memory.UserProfileStore;
import com.pegasuscorp.orbe.ui.OrbeTokens;
import com.pegasuscorp.orbe.ui.PegaseSheets;

import java.util.List;

/**
 * Vue lecture : ce que Pégase croit savoir de toi — agrège profil, souvenirs, Atlas, session.
 * L'édition reste dans Mémoire / Atlas.
 */
public class PortraitActivity extends AppCompatActivity {

    private float density;
    private LinearLayout body;

    public static void open(Context ctx) {
        Intent i = new Intent(ctx, PortraitActivity.class);
        if (!(ctx instanceof android.app.Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(OrbeTokens.COLOR_BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(14), dp(12), dp(8));
        root.addView(header, matchWrap());

        TextView title = new TextView(this);
        title.setText("Portrait");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(OrbeTokens.typeLight());
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button close = iconBtn("✕");
        close.setOnClickListener(v -> {
            PegaseSheets.haptic(v);
            finish();
        });
        header.addView(close);

        TextView subtitle = new TextView(this);
        subtitle.setText("Ce que Pégase croit savoir de toi — lecture seule");
        subtitle.setTextColor(OrbeTokens.COLOR_MUTED);
        subtitle.setTextSize(12);
        subtitle.setTypeface(OrbeTokens.typeLight());
        subtitle.setPadding(dp(16), 0, dp(16), dp(10));
        root.addView(subtitle);

        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), 0, dp(16), dp(24));
        scroll.addView(body, matchWrap());
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(16), dp(8), dp(16), dp(12));
        root.addView(footer, matchWrap());

        footer.addView(linkBtn("Modifier la mémoire", () ->
                startActivity(new Intent(this, MemorySettingsActivity.class))));
        footer.addView(spacer(6));
        footer.addView(linkBtn("Modifier l'Atlas", () ->
                startActivity(new Intent(this, AtlasSettingsActivity.class))));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        setContentView(root);
        refreshPortrait();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPortrait();
    }

    private void refreshPortrait() {
        if (body == null) return;
        body.removeAllViews();
        buildPortrait(body);
    }

    private void buildPortrait(LinearLayout body) {
        UserProfileStore profile = UserProfileStore.getInstance(this);
        MemoryRepository memory = MemoryRepository.getInstance(this);
        EntityStore atlas = EntityStore.getInstance(this);

        // Qui tu es
        body.addView(sectionTitle("Qui tu es"));
        LinearLayout who = card();
        who.addView(line("Prénom", profile.getUserName()));
        who.addView(line("Assistant", profile.getAssistantName()));
        String personality = profile.getAssistantPersonality();
        if (personality != null && !personality.isEmpty()) {
            who.addView(line("Ton", clip(personality, 140)));
        }
        List<String> projects = profile.getProjectsList();
        if (!projects.isEmpty()) {
            who.addView(line("Projets", join(projects, 4)));
        }
        List<String> interests = profile.getInterestsList();
        if (!interests.isEmpty()) {
            who.addView(line("Intérêts", join(interests, 4)));
        }
        List<String> notes = profile.getNotesList();
        if (!notes.isEmpty()) {
            who.addView(line("Notes", join(notes, 3)));
        }
        body.addView(who);
        body.addView(spacer(14));

        // Ce qu'elle retient
        body.addView(sectionTitle("Ce qu'elle retient"));
        List<MemoryEntry> memories = memory.getTopPermanentMemories(12);
        if (memories.isEmpty()) {
            body.addView(emptyCard("Aucun souvenir permanent pour l'instant."));
        } else {
            LinearLayout memCard = card();
            for (int i = 0; i < memories.size(); i++) {
                MemoryEntry e = memories.get(i);
                String content = e.content != null ? e.content.trim() : "";
                if (content.isEmpty()) continue;
                TextView row = new TextView(this);
                row.setText("· " + clip(content, 120));
                row.setTextColor(Color.WHITE);
                row.setTextSize(13);
                row.setTypeface(OrbeTokens.typeLight());
                row.setPadding(0, i == 0 ? 0 : dp(6), 0, 0);
                memCard.addView(row, matchWrap());
            }
            int total = memory.getAllPermanentMemories().size();
            if (total > memories.size()) {
                TextView more = new TextView(this);
                more.setText("+" + (total - memories.size()) + " autres — voir Mémoire");
                more.setTextColor(OrbeTokens.COLOR_CYAN);
                more.setTextSize(12);
                more.setPadding(0, dp(8), 0, 0);
                more.setOnClickListener(v ->
                        startActivity(new Intent(this, MemorySettingsActivity.class)));
                memCard.addView(more, matchWrap());
            }
            body.addView(memCard);
        }
        body.addView(spacer(14));

        // Qui compte (Atlas)
        body.addView(sectionTitle("Qui compte"));
        List<Entity> entities = atlas.getAll();
        int shown = 0;
        LinearLayout atlasCard = card();
        for (Entity e : entities) {
            if (e == null || e.name == null || e.name.isEmpty()) continue;
            if ("pegase".equalsIgnoreCase(e.id) || "orbe".equalsIgnoreCase(e.id)) {
                // Skip meta self-entities if seeded — still show if few entities
            }
            String relation = e.data != null ? e.data.optString("relation", "").trim() : "";
            String label = Entity.typeLabelFr(e.type) + " · " + e.name;
            if (!relation.isEmpty()) label += " — " + relation;
            TextView row = new TextView(this);
            row.setText("· " + clip(label, 100));
            row.setTextColor(Color.WHITE);
            row.setTextSize(13);
            row.setTypeface(OrbeTokens.typeLight());
            row.setPadding(0, shown == 0 ? 0 : dp(6), 0, 0);
            atlasCard.addView(row, matchWrap());
            shown++;
            if (shown >= 8) break;
        }
        if (shown == 0) {
            body.addView(emptyCard("Atlas vide — ajoute des personnes ou projets."));
        } else {
            if (entities.size() > shown) {
                TextView more = new TextView(this);
                more.setText("+" + (entities.size() - shown) + " fiches — voir Atlas");
                more.setTextColor(OrbeTokens.COLOR_CYAN);
                more.setTextSize(12);
                more.setPadding(0, dp(8), 0, 0);
                more.setOnClickListener(v ->
                        startActivity(new Intent(this, AtlasSettingsActivity.class)));
                atlasCard.addView(more, matchWrap());
            }
            body.addView(atlasCard);
        }
        body.addView(spacer(14));

        // Fil récent
        body.addView(sectionTitle("Fil récent"));
        SessionSummary latest = memory.getLatestSessionSummary();
        if (latest == null || (isBlank(latest.topic) && isBlank(latest.summary))) {
            body.addView(emptyCard("Pas encore de résumé de session."));
        } else {
            LinearLayout sess = card();
            if (!isBlank(latest.topic)) {
                sess.addView(line("Sujet", latest.topic.trim()));
            }
            if (!isBlank(latest.summary)) {
                sess.addView(line("Résumé", clip(latest.summary.trim(), 220)));
            }
            if (!isBlank(latest.endedAt)) {
                sess.addView(line("Fin", latest.endedAt));
            }
            body.addView(sess);
        }
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(OrbeTokens.COLOR_CYAN);
        t.setTextSize(13);
        t.setTypeface(OrbeTokens.typeMedium());
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(OrbeTokens.COLOR_CARD);
        bg.setCornerRadius(dp(OrbeTokens.RADIUS_MD));
        bg.setStroke(dp(1), OrbeTokens.COLOR_SEP);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = matchWrap();
        card.setLayoutParams(lp);
        return card;
    }

    private View emptyCard(String msg) {
        LinearLayout c = card();
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextColor(OrbeTokens.COLOR_MUTED);
        t.setTextSize(13);
        t.setTypeface(OrbeTokens.typeLight());
        c.addView(t, matchWrap());
        return c;
    }

    private View line(String label, String value) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, dp(8));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(OrbeTokens.COLOR_MUTED);
        l.setTextSize(11);
        col.addView(l, matchWrap());
        TextView v = new TextView(this);
        v.setText(value != null ? value : "—");
        v.setTextColor(Color.WHITE);
        v.setTextSize(14);
        v.setTypeface(OrbeTokens.typeLight());
        col.addView(v, matchWrap());
        return col;
    }

    private Button linkBtn(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(OrbeTokens.COLOR_CYAN);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(OrbeTokens.COLOR_CARD);
        bg.setCornerRadius(dp(OrbeTokens.RADIUS_SM));
        bg.setStroke(dp(1), OrbeTokens.COLOR_SEP);
        b.setBackground(bg);
        b.setOnClickListener(v -> {
            PegaseSheets.haptic(v);
            if (action != null) action.run();
        });
        return b;
    }

    private Button iconBtn(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinWidth(dp(40));
        b.setMinHeight(dp(40));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(OrbeTokens.COLOR_CARD);
        b.setBackground(bg);
        b.setTextColor(Color.WHITE);
        return b;
    }

    private View spacer(int dpH) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(dpH)));
        return v;
    }

    private static String join(List<String> items, int max) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(max, items.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(" · ");
            sb.append(items.get(i).trim());
        }
        if (items.size() > max) sb.append(" · …");
        return sb.toString();
    }

    private static String clip(String s, int max) {
        if (s == null) return "—";
        String t = s.trim();
        if (t.length() <= max) return t;
        return t.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private int dp(int v) {
        return (int) (v * density);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
