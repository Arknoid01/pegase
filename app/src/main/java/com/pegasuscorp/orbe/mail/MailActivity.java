package com.pegasuscorp.orbe.mail;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.pegasuscorp.orbe.ui.OrbeTokens;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mails : toutes les boîtes Gmail dans une seule liste (onglet Principale de chacune),
 * lecture seule. Un compte = adresse + mot de passe d'application.
 */
public class MailActivity extends AppCompatActivity {

    static final int MAX_PER_ACCOUNT = 40;
    /** L'outil Pégase relève moins de mails (réponse plus rapide). */
    public static final int MAX_PER_ACCOUNT_FOR_TOOL = 25;
    private static final int MAX_ROWS = 120;
    private static final int[] ACCOUNT_COLORS = {0xFFEA4335, 0xFF4F8DFF, 0xFFFBBC05, 0xFF34A853};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout chips;
    private LinearLayout column;
    private TextView status;
    /** Compte affiché : {@code null} = toutes les boîtes. */
    private String filter;
    private boolean refreshing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(OrbeTokens.COLOR_BG);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(14), dp(12), dp(4));
        TextView title = new TextView(this);
        title.setText("Mails");
        title.setTextColor(Color.WHITE);
        title.setTypeface(OrbeTokens.typeMedium());
        title.setTextSize(20f);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView refresh = pill("⟳ Actualiser");
        refresh.setOnClickListener(v -> refresh());
        top.addView(refresh);
        TextView add = pill("＋ Compte");
        add.setOnClickListener(v -> askAccount());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(8);
        top.addView(add, alp);
        page.addView(top);

        status = new TextView(this);
        status.setTextColor(OrbeTokens.COLOR_MUTED_TEXT);
        status.setTextSize(12f);
        status.setPadding(dp(16), dp(2), dp(16), dp(6));
        page.addView(status);

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(12), 0, dp(12), dp(8));
        chipScroll.addView(chips);
        page.addView(chipScroll);

        ScrollView scroll = new ScrollView(this);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(12), 0, dp(12), dp(16));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ViewCompat.setOnApplyWindowInsetsListener(page, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        setContentView(page);

        render();
        if (MailAccountStore.accounts(this).isEmpty()) askAccount();
        else refresh();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ── Données ──────────────────────────────────────────────────────────

    private void refresh() {
        if (refreshing) return;
        List<String> accounts = MailAccountStore.accounts(this);
        if (accounts.isEmpty()) return;
        refreshing = true;
        status.setText("Actualisation…");
        android.content.Context app = getApplicationContext();
        io.execute(() -> {
            List<String> errors = new ArrayList<>();
            for (String a : accounts) {
                try {
                    List<MailMessage> mails = GmailImapClient.fetchLatest(a,
                            MailAccountStore.password(app, a), MAX_PER_ACCOUNT);
                    MailCache.save(app, a, mails, System.currentTimeMillis());
                } catch (Exception e) {
                    errors.add(a + " : " + MailErrors.describe(e));
                }
            }
            main.post(() -> {
                refreshing = false;
                if (isDestroyed()) return;
                render();
                if (!errors.isEmpty()) status.setText(TextUtils.join("\n", errors));
            });
        });
    }

    private void render() {
        List<String> accounts = MailAccountStore.accounts(this);
        chips.removeAllViews();
        addChip("Toutes", null);
        for (String a : accounts) addChip(shortName(a), a);

        List<MailMessage> all = new ArrayList<>();
        for (String a : accounts) {
            if (filter == null || filter.equals(a)) all.addAll(MailCache.load(this, a));
        }
        all.sort((x, y) -> Long.compare(y.dateMs, x.dateMs));
        int unread = 0;
        for (MailMessage m : all) if (!m.seen) unread++;
        status.setText(accounts.isEmpty() ? "Ajoute un compte Gmail pour commencer."
                : (unread == 0 ? "Tout est lu" : unread + (unread > 1 ? " non lus" : " non lu"))
                + " · onglet Principale · appui long sur une boîte : la retirer");

        column.removeAllViews();
        int n = 0;
        for (MailMessage m : all) {
            if (n++ >= MAX_ROWS) break;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            column.addView(buildRow(m, accounts.indexOf(m.account)), lp);
        }
    }

    // ── Vues ─────────────────────────────────────────────────────────────

    private void addChip(String label, String account) {
        TextView t = pill(label);
        boolean active = account == null ? filter == null : account.equals(filter);
        GradientDrawable d = (GradientDrawable) t.getBackground();
        d.setColor(active ? OrbeTokens.COLOR_CYAN : OrbeTokens.COLOR_CHIP_BG);
        t.setTextColor(active ? Color.BLACK : Color.WHITE);
        t.setOnClickListener(v -> {
            filter = account;
            render();
        });
        if (account != null) {
            t.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Retirer " + account + " ?")
                        .setMessage("Pégase oublie l'adresse et son mot de passe d'application. "
                                + "Pense aussi à révoquer le code dans ton compte Google.")
                        .setPositiveButton("Retirer", (dlg, w) -> {
                            MailAccountStore.remove(this, account);
                            if (account.equals(filter)) filter = null;
                            render();
                        })
                        .setNegativeButton("Annuler", null)
                        .show();
                return true;
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (chips.getChildCount() > 0) lp.leftMargin = dp(8);
        chips.addView(t, lp);
    }

    private View buildRow(MailMessage m, int accountIndex) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(10), dp(10), dp(12), dp(10));
        row.setBackground(card(m.seen ? 0x0FFFFFFF : 0x1A35D0DD));
        row.setOnClickListener(v -> openMail(m));

        View dot = new View(this);
        GradientDrawable c = new GradientDrawable();
        c.setShape(GradientDrawable.OVAL);
        c.setColor(ACCOUNT_COLORS[Math.max(0, accountIndex) % ACCOUNT_COLORS.length]);
        dot.setBackground(c);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dlp.topMargin = dp(5);
        row.addView(dot, dlp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, 0, 0);
        LinearLayout line1 = new LinearLayout(this);
        TextView from = new TextView(this);
        from.setText(m.sender());
        from.setTextColor(Color.WHITE);
        from.setTypeface(m.seen ? null : OrbeTokens.typeMedium());
        from.setTextSize(14f);
        from.setSingleLine(true);
        from.setEllipsize(TextUtils.TruncateAt.END);
        line1.addView(from, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView when = new TextView(this);
        when.setText(m.dateMs > 0 ? DateUtils.getRelativeTimeSpanString(m.dateMs) : "");
        when.setTextColor(m.seen ? OrbeTokens.COLOR_MUTED : OrbeTokens.COLOR_CYAN);
        when.setTextSize(11f);
        when.setPadding(dp(8), 0, 0, 0);
        line1.addView(when);
        texts.addView(line1);

        TextView subject = new TextView(this);
        subject.setText(m.subject.isEmpty() ? "(sans objet)" : m.subject);
        subject.setTextColor(m.seen ? OrbeTokens.COLOR_MUTED_TEXT : 0xEEFFFFFF);
        subject.setTextSize(13f);
        subject.setSingleLine(true);
        subject.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(subject);
        if (!m.snippet.isEmpty()) {
            TextView snip = new TextView(this);
            snip.setText(m.snippet);
            snip.setTextColor(OrbeTokens.COLOR_MUTED_TEXT);
            snip.setTextSize(12f);
            snip.setMaxLines(2);
            snip.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(snip);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    // ── Actions ──────────────────────────────────────────────────────────

    private void openMail(MailMessage m) {
        TextView body = new TextView(this);
        body.setText("Chargement…");
        body.setTextColor(Color.WHITE);
        body.setTextSize(14f);
        body.setTextIsSelectable(true);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(m.subject.isEmpty() ? "(sans objet)" : m.subject)
                .setView(sv)
                .setPositiveButton("Fermer", null)
                .setNeutralButton("Ouvrir Gmail", (d, w) -> openGmail())
                .show();
        String header = "De : " + m.sender()
                + (m.fromName.isEmpty() ? "" : " <" + m.fromAddress + ">")
                + "\nÀ : " + m.account + "\n\n";
        android.content.Context app = getApplicationContext();
        io.execute(() -> {
            String text;
            try {
                text = GmailImapClient.readBody(m.account,
                        MailAccountStore.password(app, m.account), m.uid);
                if (text.isEmpty()) text = "(mail sans texte : pièce jointe ou image seulement)";
            } catch (Exception e) {
                text = "Lecture impossible : " + MailErrors.describe(e);
            }
            String t = text;
            main.post(() -> {
                if (dlg.isShowing()) body.setText(header + t);
            });
        });
    }

    private void openGmail() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.google.android.gm");
        if (i != null) startActivity(i);
    }

    private void askAccount() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        TextView help = new TextView(this);
        help.setText("Adresse Gmail + mot de passe d'application (16 lettres), créé sur "
                + "myaccount.google.com/apppasswords. Il reste chiffré sur ce téléphone.");
        help.setTextSize(13f);
        form.addView(help);
        TextView link = new TextView(this);
        link.setText("Créer le mot de passe d'application ›");
        link.setTextColor(OrbeTokens.COLOR_CYAN);
        link.setPadding(0, dp(8), 0, dp(8));
        link.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://myaccount.google.com/apppasswords"))));
        form.addView(link);
        EditText email = new EditText(this);
        email.setHint("adresse@gmail.com");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        form.addView(email);
        EditText pass = new EditText(this);
        pass.setHint("mot de passe d'application");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(pass);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Ajouter une boîte Gmail")
                .setView(form)
                .setPositiveButton("Vérifier et ajouter", null)
                .setNegativeButton("Annuler", null)
                .show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String e = email.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            String p = MailAccountStore.normalizePassword(pass.getText().toString());
            if (!e.contains("@") || p.isEmpty()) {
                help.setText("Il faut l'adresse et le mot de passe d'application.");
                return;
            }
            v.setEnabled(false);
            help.setText("Connexion à Gmail…");
            io.execute(() -> {
                String error = null;
                try {
                    GmailImapClient.check(e, p);
                } catch (Exception ex) {
                    error = MailErrors.describe(ex);
                }
                String err = error;
                main.post(() -> {
                    if (err != null) {
                        help.setText("Échec : " + err);
                        v.setEnabled(true);
                        return;
                    }
                    MailAccountStore.add(this, e, p);
                    dlg.dismiss();
                    render();
                    refresh();
                });
            });
        });
    }

    // ── Helpers UI ───────────────────────────────────────────────────────

    private static String shortName(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private TextView pill(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTypeface(OrbeTokens.typeMedium());
        t.setTextSize(13f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(16));
        d.setColor(OrbeTokens.COLOR_CHIP_BG);
        d.setStroke(dp(1), OrbeTokens.COLOR_CHIP_STROKE);
        t.setBackground(d);
        return t;
    }

    private GradientDrawable card(int colorArgb) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(OrbeTokens.RADIUS_LG));
        d.setColor(colorArgb);
        d.setStroke(dp(1), OrbeTokens.COLOR_CHIP_STROKE);
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
