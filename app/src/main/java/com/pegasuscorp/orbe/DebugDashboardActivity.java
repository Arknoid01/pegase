package com.pegasuscorp.orbe;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.pegasuscorp.orbe.diag.DebugHealthSnapshot;
import com.pegasuscorp.orbe.diag.DebugProblemAckStore;
import com.pegasuscorp.orbe.diag.PegaseDiagLog;
import com.pegasuscorp.orbe.diag.Trace;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tableau de bord debug : santé, problèmes (actifs / résolus), filtre date, tri.
 * Lecture seule des JSONL — acquittement local pour la progression.
 */
public final class DebugDashboardActivity extends AppCompatActivity {

    private static final int BG = 0xFF0B0F14;
    private static final int CARD = 0xFF141A22;
    private static final int TEXT = 0xFFE8EEF6;
    private static final int MUTED = 0xFF8B9BB0;
    private static final int OK = 0xFF3DDC97;
    private static final int WARN = 0xFFE8B84A;
    private static final int BAD = 0xFFE85D5D;
    private static final int CHIP_ON = 0xFF2A3A4F;
    private static final int CHIP_OFF = 0xFF1C2633;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private LinearLayout filterHost;
    private TextView statusLine;
    private String lastSummary = "";
    private DebugHealthSnapshot.Window window = DebugHealthSnapshot.Window.H24;
    private DebugHealthSnapshot.Sort sort = DebugHealthSnapshot.Sort.NEWEST;
    private DebugHealthSnapshot lastSnap;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Trace.init(this);
        window = DebugHealthSnapshot.Window.fromId(DebugProblemAckStore.getWindow(this));
        sort = DebugHealthSnapshot.Sort.fromId(DebugProblemAckStore.getSort(this));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        buildChrome();
        refresh();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void buildChrome() {
        TextView title = label("Debug", 22, true, TEXT);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title);

        statusLine = label("Chargement…", 13, false, MUTED);
        statusLine.setPadding(0, 0, 0, dp(10));
        root.addView(statusLine);

        root.addView(sectionTitle("Période"));
        filterHost = new LinearLayout(this);
        filterHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(filterHost);
        rebuildFilters();

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(10);
        actions.setLayoutParams(alp);
        actions.addView(actionBtn("Actualiser", this::refresh));
        actions.addView(spacer(8));
        actions.addView(actionBtn("Copier", () -> copy(lastSummary)));
        actions.addView(spacer(8));
        actions.addView(actionBtn("Partager", () -> {
            try {
                PegaseDiagLog.shareLogs(this);
            } catch (Exception e) {
                Toast.makeText(this, "Partage impossible", Toast.LENGTH_SHORT).show();
            }
        }));
        root.addView(actions);

        View divider = new View(this);
        LinearLayout.LayoutParams dpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dpLp.topMargin = dp(16);
        dpLp.bottomMargin = dp(8);
        divider.setLayoutParams(dpLp);
        divider.setBackgroundColor(0xFF243040);
        root.addView(divider);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setTag("content");
        root.addView(content);
    }

    private void rebuildFilters() {
        filterHost.removeAllViews();

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (DebugHealthSnapshot.Window w : DebugHealthSnapshot.Window.values()) {
            boolean on = w == window;
            TextView chip = chip(w.label, on, () -> {
                window = w;
                DebugProblemAckStore.setWindow(this, w.id);
                rebuildFilters();
                refresh();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            row.addView(chip);
        }
        hsv.addView(row);
        filterHost.addView(hsv);

        HorizontalScrollView hsv2 = new HorizontalScrollView(this);
        hsv2.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams hsv2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hsv2Lp.topMargin = dp(8);
        hsv2.setLayoutParams(hsv2Lp);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        for (DebugHealthSnapshot.Sort s : DebugHealthSnapshot.Sort.values()) {
            boolean on = s == sort;
            TextView chip = chip(s.label, on, () -> {
                sort = s;
                DebugProblemAckStore.setSort(this, s.id);
                rebuildFilters();
                refresh();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            row2.addView(chip);
        }
        TextView clearAck = chip("Réafficher acquittés", false, () -> {
            DebugProblemAckStore.clearAcks(this);
            Toast.makeText(this, "Acquittements effacés", Toast.LENGTH_SHORT).show();
            refresh();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(8);
        clearAck.setLayoutParams(clp);
        row2.addView(clearAck);
        hsv2.addView(row2);
        filterHost.addView(hsv2);
    }

    private LinearLayout contentHost() {
        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if ("content".equals(v.getTag()) && v instanceof LinearLayout) {
                return (LinearLayout) v;
            }
        }
        return root;
    }

    private void refresh() {
        statusLine.setText("Actualisation…");
        final DebugHealthSnapshot.Window w = window;
        final DebugHealthSnapshot.Sort s = sort;
        io.execute(() -> {
            DebugHealthSnapshot snap = DebugHealthSnapshot.capture(this, w, s);
            main.post(() -> render(snap));
        });
    }

    private void render(DebugHealthSnapshot s) {
        lastSnap = s;
        statusLine.setText("Mis à jour · " + s.generatedAt + " · " + s.window.label);
        lastSummary = buildSummary(s);

        LinearLayout content = contentHost();
        content.removeAllViews();

        boolean hasProblems = !s.activeProblems.isEmpty();
        int bannerAccent = hasProblems ? BAD : OK;
        String bannerTitle = hasProblems
                ? s.activeProblems.size() + " problème(s) actif(s)"
                : "Système OK";
        content.addView(healthBanner(bannerTitle, s.healthLine, bannerAccent));

        content.addView(sectionTitle("Compteurs (" + s.window.label + ")"));
        content.addView(card(s.statsLine, MUTED));

        content.addView(sectionTitle("Problèmes actifs"));
        if (!hasProblems) {
            content.addView(card(
                    "Aucun problème actif sur cette période.\n"
                            + "Les bugs corrigés passent en « Résolus » (auto ou acquitté).",
                    OK));
        } else {
            for (DebugHealthSnapshot.Problem p : s.activeProblems) {
                content.addView(problemCard(p, true));
            }
            content.addView(actionBtn("Tout acquitter (période)", () -> {
                ArrayList<String> ids = new ArrayList<>();
                for (DebugHealthSnapshot.Problem p : s.activeProblems) ids.add(p.id);
                DebugProblemAckStore.acknowledgeAll(this, ids);
                Toast.makeText(this, "Problèmes acquis — réapparaissent si nouvelle occurrence",
                        Toast.LENGTH_LONG).show();
                refresh();
            }));
        }

        content.addView(sectionTitle("Résolus / acquittés"));
        if (s.resolvedProblems.isEmpty()) {
            content.addView(card(
                    "Rien ici pour l’instant.\n"
                            + "Auto : un succès plus récent (SCO ok, STT ready…) masque l’échec.\n"
                            + "Manuel : « Marquer résolu » — ne revient que si le bug se reproduit.",
                    MUTED));
        } else {
            for (DebugHealthSnapshot.Problem p : s.resolvedProblems) {
                content.addView(problemCard(p, false));
            }
        }

        content.addView(sectionTitle("Micro & route"));
        content.addView(card(s.micLine, WARN));

        content.addView(sectionTitle("Wake"));
        content.addView(card(s.wakeLine, MUTED));

        content.addView(sectionTitle("SCO / settle"));
        content.addView(card(s.lastScoLine, MUTED));

        content.addView(sectionTitle("Wake → STT"));
        content.addView(card(s.lastWakeSttLine, MUTED));

        content.addView(sectionTitle("Changements de route"));
        content.addView(card(s.lastRouteChangeLine, MUTED));

        content.addView(sectionTitle("Météo (diag)"));
        content.addView(card(s.lastWeatherLine, MUTED));

        content.addView(sectionTitle("Wakes (" + s.window.label + ")"));
        content.addView(eventList(s.recentWakeHits));

        content.addView(sectionTitle("SCO (détail)"));
        content.addView(eventList(s.recentScoEvents));

        content.addView(sectionTitle("Crashes"));
        content.addView(eventList(s.recentCrashes));

        content.addView(sectionTitle("Erreurs trace"));
        content.addView(eventList(s.recentErrors));

        content.addView(sectionTitle("File voice (extrait)"));
        content.addView(eventList(s.recentKwsEvents));

        TextView tip = label(
                "Les JSONL ne sont pas effacés. Acquitter = masquer l’alerte jusqu’à une nouvelle occurrence.",
                12, false, MUTED);
        tip.setPadding(0, dp(16), 0, 0);
        content.addView(tip);
    }

    private View problemCard(DebugHealthSnapshot.Problem p, boolean active) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(CARD);
        int accent = active ? BAD
                : (p.kind == DebugHealthSnapshot.ProblemKind.RESOLVED_AUTO ? OK : WARN);
        bg.setStroke(dp(1), mix(CARD, accent, 0.4f));
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        box.setLayoutParams(lp);

        String kindTag = p.kind == DebugHealthSnapshot.ProblemKind.RESOLVED_AUTO
                ? "auto"
                : (p.kind == DebugHealthSnapshot.ProblemKind.ACKED ? "acquitté" : "actif");
        TextView head = label(p.title + "  ·  " + kindTag, 13, true, accent);
        box.addView(head);

        TextView body = label(p.displayLine().contains("\n")
                ? p.displayLine().substring(p.displayLine().indexOf('\n') + 1)
                : (p.detail != null ? p.detail : ""), 12, false, TEXT);
        body.setPadding(0, dp(4), 0, 0);
        body.setTypeface(Typeface.MONOSPACE);
        box.addView(body);

        if (active) {
            TextView btn = actionBtn("Marquer résolu", () -> confirmAck(p));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(8);
            btn.setLayoutParams(blp);
            box.addView(btn);
        }
        return box;
    }

    private void confirmAck(DebugHealthSnapshot.Problem p) {
        new AlertDialog.Builder(this)
                .setTitle("Marquer résolu ?")
                .setMessage("« " + p.title + " » disparaît des alertes.\n"
                        + "Il ne revient que si le même problème se reproduit après maintenant.")
                .setPositiveButton("Résolu", (d, w) -> {
                    DebugProblemAckStore.acknowledge(this, p.id, p.eventAtMs);
                    Toast.makeText(this, "Acquitté", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private static String buildSummary(DebugHealthSnapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append("Santé: ").append(s.healthLine).append('\n');
        sb.append(s.statsLine).append('\n');
        sb.append(s.micLine).append('\n');
        sb.append(s.wakeLine).append('\n');
        if (!s.activeProblems.isEmpty()) {
            sb.append("Actifs:\n");
            for (DebugHealthSnapshot.Problem p : s.activeProblems) {
                sb.append("- ").append(p.title).append('\n');
            }
        }
        if (!s.resolvedProblems.isEmpty()) {
            sb.append("Résolus:\n");
            for (DebugHealthSnapshot.Problem p : s.resolvedProblems) {
                sb.append("- ").append(p.title).append(" [").append(p.kind).append("]\n");
            }
        }
        return sb.toString().trim();
    }

    private View healthBanner(String title, String subtitle, int accent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(mix(CARD, accent, 0.18f));
        bg.setStroke(dp(1), accent);
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        box.setLayoutParams(lp);
        box.addView(label(title, 18, true, accent));
        TextView sub = label(subtitle, 13, false, TEXT);
        sub.setPadding(0, dp(6), 0, 0);
        box.addView(sub);
        return box;
    }

    private View eventList(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return card("Aucun événement sur cette période.", MUTED);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append("\n\n");
            sb.append(lines.get(i));
        }
        TextView tv = card(sb.toString(), MUTED);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.MONOSPACE);
        return tv;
    }

    private TextView card(String text, int accent) {
        TextView tv = new TextView(this);
        tv.setText(text != null ? text : "—");
        tv.setTextColor(TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLineSpacing(dp(2), 1.05f);
        tv.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(CARD);
        bg.setStroke(dp(1), mix(CARD, accent, 0.35f));
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView sectionTitle(String t) {
        TextView tv = label(t, 14, true, MUTED);
        tv.setPadding(0, dp(12), 0, dp(6));
        return tv;
    }

    private TextView label(String t, float sp, boolean bold, int color) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        return tv;
    }

    private TextView chip(String title, boolean on, Runnable onClick) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(on ? CHIP_ON : CHIP_OFF);
        bg.setStroke(dp(1), on ? 0xFF5B7C99 : 0xFF334155);
        tv.setBackground(bg);
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private TextView actionBtn(String title, Runnable onClick) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(0xFF1C2633);
        bg.setStroke(dp(1), 0xFF334155);
        tv.setBackground(bg);
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private View spacer(int wDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp), 1));
        return v;
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("debug", text));
            Toast.makeText(this, "Résumé copié", Toast.LENGTH_SHORT).show();
        }
    }

    private static int mix(int base, int accent, float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        int br = Color.red(base), bg = Color.green(base), bb = Color.blue(base);
        int ar = Color.red(accent), ag = Color.green(accent), ab = Color.blue(accent);
        return Color.rgb(
                (int) (br + (ar - br) * a),
                (int) (bg + (ag - bg) * a),
                (int) (bb + (ab - bb) * a));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    public static Intent intent(Context ctx) {
        return new Intent(ctx, DebugDashboardActivity.class);
    }
}
