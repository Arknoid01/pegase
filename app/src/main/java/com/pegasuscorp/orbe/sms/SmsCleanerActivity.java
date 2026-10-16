package com.pegasuscorp.orbe.sms;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.pegasuscorp.orbe.FluidBackgroundView;
import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.chat.ChatBackendFactory;
import com.pegasuscorp.orbe.ui.OrbeTokens;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ménage des SMS de spam (tuile « Nettoyer SMS » du menu Pégase).
 *
 * 1. Scan : conversations à un seul SMS reçu, hors contacts ({@link SmsInbox}).
 * 2. Règles : spams probables cochés, services et récents à part ({@link SmsSpamRules}).
 * 3. Option : « Trier avec Pégase » envoie les textes au LLM pour affiner.
 * 4. Supprimer : Pégase devient app SMS par défaut le temps du ménage, puis rend la main.
 */
public class SmsCleanerActivity extends AppCompatActivity {

    private static final int REQ_PERMS = 7311;
    private static final int REQ_DEFAULT_SMS = 7312;
    private static final int LLM_BATCH = 40;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<SmsSpamRules.Candidate> candidates = new ArrayList<>();

    private LinearLayout listColumn;
    private TextView status;
    private TextView llmButton;
    private TextView deleteButton;
    private View restoreCard;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(OrbeTokens.COLOR_BG);
        FluidBackgroundView background = new FluidBackgroundView(this);
        root.addView(background, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        background.startStatic();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(16), dp(16), dp(16), dp(8));
        page.addView(top);

        TextView title = new TextView(this);
        title.setText("Nettoyer les SMS");
        title.setTextColor(Color.WHITE);
        title.setTypeface(OrbeTokens.typeMedium());
        title.setTextSize(20f);
        top.addView(title);

        status = new TextView(this);
        status.setTextColor(OrbeTokens.COLOR_MUTED_TEXT);
        status.setTextSize(13f);
        status.setPadding(0, dp(4), 0, 0);
        top.addView(status);

        restoreCard = buildRestoreCard();
        top.addView(restoreCard, spacedAbove(10));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        listColumn = new LinearLayout(this);
        listColumn.setOrientation(LinearLayout.VERTICAL);
        listColumn.setPadding(dp(16), 0, dp(16), dp(16));
        scroll.addView(listColumn, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(16), dp(8), dp(16), dp(16));
        llmButton = button("✨ Trier avec Pégase", 0x1435D0DD);
        llmButton.setOnClickListener(v -> askLlm());
        actions.addView(llmButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        deleteButton = button("Supprimer", 0x33E05555);
        deleteButton.setOnClickListener(v -> confirmDelete());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dlp.leftMargin = dp(10);
        actions.addView(deleteButton, dlp);
        page.addView(actions);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            page.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        setContentView(root);

        if (hasSmsPermission()) {
            scan();
        } else {
            status.setText("Pégase a besoin de lire tes SMS (et tes contacts pour les épargner).");
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS}, REQ_PERMS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreCard.setVisibility(SmsInbox.isDefaultSmsApp(this) ? View.VISIBLE : View.GONE);
    }

    private boolean hasSmsPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        if (hasSmsPermission()) scan();
        else status.setText("Sans accès aux SMS, je ne peux rien trier.");
    }

    // ── Scan ─────────────────────────────────────────────────────────────

    private void scan() {
        busy = true;
        status.setText("Je regarde tes SMS…");
        refreshButtons();
        io.execute(() -> {
            List<SmsSpamRules.Candidate> found = null;
            String error = null;
            try {
                found = SmsInbox.scan(getApplicationContext());
            } catch (Exception e) {
                error = e.getMessage();
            }
            List<SmsSpamRules.Candidate> result = found;
            String failure = error;
            main.post(() -> {
                busy = false;
                if (result == null) {
                    status.setText("Lecture des SMS impossible : " + failure);
                    refreshButtons();
                    return;
                }
                candidates.clear();
                candidates.addAll(result);
                render();
            });
        });
    }

    // ── Tri LLM ──────────────────────────────────────────────────────────

    private void askLlm() {
        if (busy || candidates.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Trier avec Pégase ?")
                .setMessage("Le texte de ces " + candidates.size() + " SMS et leur expéditeur "
                        + "seront envoyés à ton fournisseur LLM (Groq, Cerebras… selon tes "
                        + "réglages) pour qu'il repère les arnaques.")
                .setPositiveButton("Trier", (d, w) -> runLlmBatch(0))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void runLlmBatch(int from) {
        if (from >= candidates.size()) {
            busy = false;
            render();
            return;
        }
        busy = true;
        int to = Math.min(from + LLM_BATCH, candidates.size());
        status.setText("Pégase trie… " + from + " / " + candidates.size());
        refreshButtons();
        List<SmsSpamRules.Candidate> batch = new ArrayList<>(candidates.subList(from, to));
        ChatBackend backend = ChatBackendFactory.create(this);
        backend.send(Collections.emptyList(), SmsSpamRules.buildLlmPrompt(batch),
                new ChatBackend.OnReply() {
                    @Override
                    public void onReply(String text) {
                        main.post(() -> {
                            Map<Long, String[]> verdicts = SmsSpamRules.parseLlmVerdicts(text);
                            for (SmsSpamRules.Candidate c : batch) {
                                String[] v = verdicts.get(c.threadId);
                                if (v != null) {
                                    SmsSpamRules.applyVerdict(c,
                                            SmsSpamRules.verdictOf(v[0]), v[1]);
                                }
                            }
                            runLlmBatch(to);
                        });
                    }

                    @Override
                    public void onError(String error) {
                        main.post(() -> {
                            busy = false;
                            render();
                            status.setText("Le tri IA a échoué : " + error
                                    + "\nLes règles simples restent appliquées.");
                        });
                    }
                });
    }

    // ── Suppression ──────────────────────────────────────────────────────

    private List<Long> checkedThreads() {
        List<Long> ids = new ArrayList<>();
        for (SmsSpamRules.Candidate c : candidates) if (c.checked) ids.add(c.threadId);
        return ids;
    }

    private void confirmDelete() {
        if (busy) return;
        int n = checkedThreads().size();
        if (n == 0) return;
        String msg = "Supprimer définitivement " + n + " conversation" + (n > 1 ? "s" : "") + " ?";
        if (!SmsInbox.isDefaultSmsApp(this)) {
            msg += "\n\nAndroid réserve la suppression à l'app SMS par défaut : Pégase va "
                    + "te demander de le devenir le temps du ménage, puis tu rendras la main "
                    + "à Messages.";
        }
        new AlertDialog.Builder(this)
                .setTitle("Ménage")
                .setMessage(msg)
                .setPositiveButton("Supprimer", (d, w) -> {
                    if (SmsInbox.isDefaultSmsApp(this)) {
                        deleteChecked();
                    } else {
                        SmsInbox.rememberPreviousDefault(this);
                        startActivityForResult(SmsInbox.requestDefaultIntent(this),
                                REQ_DEFAULT_SMS);
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_DEFAULT_SMS) return;
        if (resultCode == RESULT_OK || SmsInbox.isDefaultSmsApp(this)) {
            deleteChecked();
        } else {
            say("Pégase n'est pas devenu l'app SMS : rien n'a été supprimé.");
        }
    }

    private void deleteChecked() {
        List<Long> ids = checkedThreads();
        busy = true;
        status.setText("Suppression de " + ids.size() + " conversations…");
        refreshButtons();
        io.execute(() -> {
            int deleted = SmsInbox.deleteThreads(getApplicationContext(), ids);
            if (deleted == 0 && !ids.isEmpty()) {
                main.post(() -> {
                    busy = false;
                    refreshButtons();
                    say("Android a refusé la suppression (0 / " + ids.size() + ").");
                });
                return;
            }
            main.post(() -> {
                busy = false;
                List<SmsSpamRules.Candidate> kept = new ArrayList<>();
                for (SmsSpamRules.Candidate c : candidates) if (!c.checked) kept.add(c);
                candidates.clear();
                candidates.addAll(kept);
                render();
                status.setText(deleted + " conversation" + (deleted > 1 ? "s" : "")
                        + " supprimée" + (deleted > 1 ? "s" : "") + ". Pense à rendre la main à Messages.");
                restoreCard.setVisibility(View.VISIBLE);
                promptRestore();
            });
        });
    }

    private void promptRestore() {
        new AlertDialog.Builder(this)
                .setTitle("Rendre la main à Messages")
                .setMessage("Dans « Application SMS », choisis Messages : sinon tes nouveaux "
                        + "SMS arriveront dans Pégase (ils sont gardés, mais sans les "
                        + "conversations de Messages).")
                .setPositiveButton("Ouvrir le réglage",
                        (d, w) -> startActivity(SmsInbox.restoreDefaultIntent(this)))
                .setNegativeButton("Plus tard", null)
                .show();
    }

    // ── Rendu ────────────────────────────────────────────────────────────

    private void render() {
        listColumn.removeAllViews();
        if (candidates.isEmpty()) {
            status.setText("Aucun SMS isolé d'un inconnu. Boîte propre ✨");
            refreshButtons();
            return;
        }
        status.setText(candidates.size() + " conversations à un seul SMS d'un inconnu. "
                + "Les spams probables sont cochés : vérifie, puis supprime.");
        addSection("🚫 Spams probables", "Numéro classique, hors contacts",
                SmsSpamRules.Category.LIKELY_SPAM);
        addSection("🏷️ Services", "Expéditeur en lettres ou numéro court : souvent légitime",
                SmsSpamRules.Category.SERVICE);
        refreshButtons();
    }

    private void addSection(String title, String hint, SmsSpamRules.Category cat) {
        List<SmsSpamRules.Candidate> items = new ArrayList<>();
        for (SmsSpamRules.Candidate c : candidates) if (c.category == cat) items.add(c);
        if (items.isEmpty()) return;

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(title + " (" + items.size() + ")");
        t.setTextColor(Color.WHITE);
        t.setTypeface(OrbeTokens.typeMedium());
        t.setTextSize(15f);
        header.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView toggle = new TextView(this);
        toggle.setText("Tout cocher");
        toggle.setTextColor(OrbeTokens.COLOR_CYAN);
        toggle.setTextSize(13f);
        toggle.setPadding(dp(8), dp(6), 0, dp(6));
        toggle.setOnClickListener(v -> {
            boolean all = true;
            for (SmsSpamRules.Candidate c : items) all &= c.checked;
            for (SmsSpamRules.Candidate c : items) c.checked = !all;
            render();
        });
        header.addView(toggle);
        listColumn.addView(header, spacedAbove(16));

        TextView h = new TextView(this);
        h.setText(hint);
        h.setTextColor(OrbeTokens.COLOR_MUTED);
        h.setTextSize(11f);
        listColumn.addView(h);

        for (SmsSpamRules.Candidate c : items) listColumn.addView(buildRow(c), spacedAbove(8));
    }

    private View buildRow(SmsSpamRules.Candidate c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(10), dp(12), dp(10));
        row.setBackground(card(c.checked ? 0x22E05555 : 0x0FFFFFFF));

        CheckBox box = new CheckBox(this);
        box.setChecked(c.checked);
        box.setButtonTintList(android.content.res.ColorStateList.valueOf(OrbeTokens.COLOR_CYAN));
        box.setOnCheckedChangeListener((b, checked) -> {
            c.checked = checked;
            row.setBackground(card(checked ? 0x22E05555 : 0x0FFFFFFF));
            refreshButtons();
        });
        row.addView(box);
        row.setOnClickListener(v -> box.toggle());

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(4), 0, 0, 0);

        LinearLayout line1 = new LinearLayout(this);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        TextView from = new TextView(this);
        from.setText(c.address);
        from.setTextColor(Color.WHITE);
        from.setTypeface(OrbeTokens.typeMedium());
        from.setTextSize(13f);
        from.setSingleLine(true);
        from.setEllipsize(TextUtils.TruncateAt.END);
        line1.addView(from, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView when = new TextView(this);
        when.setText(DateUtils.getRelativeTimeSpanString(c.date));
        when.setTextColor(OrbeTokens.COLOR_MUTED);
        when.setTextSize(11f);
        line1.addView(when);
        texts.addView(line1);

        TextView body = new TextView(this);
        body.setText(c.body);
        body.setTextColor(OrbeTokens.COLOR_MUTED_TEXT);
        body.setTextSize(12f);
        body.setMaxLines(3);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setPadding(0, dp(2), 0, 0);
        texts.addView(body);

        if (c.verdict != SmsSpamRules.Verdict.NONE) {
            TextView verdict = new TextView(this);
            String label = c.verdict == SmsSpamRules.Verdict.SPAM ? "✨ Spam"
                    : c.verdict == SmsSpamRules.Verdict.LEGIT ? "✨ Légitime" : "✨ Doute";
            verdict.setText(c.reason.isEmpty() ? label : label + " — " + c.reason);
            verdict.setTextColor(c.verdict == SmsSpamRules.Verdict.SPAM ? 0xFFFF8A80
                    : c.verdict == SmsSpamRules.Verdict.LEGIT ? 0xFF9BE7A0 : 0xFFFFD180);
            verdict.setTextSize(11f);
            verdict.setPadding(0, dp(4), 0, 0);
            texts.addView(verdict);
        }

        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View buildRestoreCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        c.setBackground(card(0x33FFB74D));
        TextView t = new TextView(this);
        t.setText("Pégase est encore ton app SMS. Rends la main à Messages ›");
        t.setTextColor(Color.WHITE);
        t.setTextSize(13f);
        c.addView(t);
        c.setOnClickListener(v -> startActivity(SmsInbox.restoreDefaultIntent(this)));
        c.setVisibility(View.GONE);
        return c;
    }

    private void refreshButtons() {
        int n = checkedThreads().size();
        deleteButton.setText(n == 0 ? "Supprimer" : "Supprimer (" + n + ")");
        deleteButton.setAlpha(busy || n == 0 ? 0.4f : 1f);
        llmButton.setAlpha(busy || candidates.isEmpty() ? 0.4f : 1f);
    }

    // ── Helpers UI ───────────────────────────────────────────────────────

    /** Message important : dans le statut ET en toast (le statut seul passe inaperçu). */
    private void say(String text) {
        status.setText(text);
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show();
    }

    private TextView button(String label, int colorArgb) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTypeface(OrbeTokens.typeMedium());
        b.setTextSize(14f);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(14), dp(12), dp(14));
        b.setBackground(card(colorArgb));
        return b;
    }

    private GradientDrawable card(int colorArgb) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(OrbeTokens.RADIUS_LG));
        d.setColor(colorArgb);
        d.setStroke(dp(1), Color.parseColor(OrbeTokens.CHIP_STROKE));
        return d;
    }

    private LinearLayout.LayoutParams spacedAbove(int dpValue) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(dpValue);
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
