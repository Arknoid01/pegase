package com.pegasuscorp.orbe.tools.device;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.pegasuscorp.orbe.mail.GmailImapClient;
import com.pegasuscorp.orbe.mail.MailAccountStore;
import com.pegasuscorp.orbe.mail.MailActivity;
import com.pegasuscorp.orbe.mail.MailCache;
import com.pegasuscorp.orbe.mail.MailErrors;
import com.pegasuscorp.orbe.mail.MailMessage;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.ToolTag;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Mails Gmail (onglet Principale de chaque boîte), lecture seule, relevés en direct en IMAP.
 */
public final class MailsTool implements Tool {

    private static final int MAX_LISTED = 12;

    @Override
    public String id() {
        return "mails";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.EMAIL;
    }

    @Override
    public String description() {
        return "mails(action:\"unread\"|\"list\"|\"open\", compte?:str) — mails Gmail reçus "
                + "(onglet Principale de toutes les boîtes ; compte = filtre sur une adresse). "
                + "unread=non lus ; list=derniers mails ; open=ouvrir l'écran Mails.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String action = params != null ? params.optString("action", "unread").trim().toLowerCase() : "unread";
        String compte = params != null ? params.optString("compte", "").trim().toLowerCase() : "";
        if (action.equals("open") || action.equals("ouvrir")) {
            ctx.startActivity(new Intent(ctx, MailActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            cb.onSuccessAndExit(ToolResult.text("J'ouvre tes mails."));
            return;
        }
        List<String> accounts = new ArrayList<>();
        for (String a : MailAccountStore.accounts(ctx)) {
            if (compte.isEmpty() || a.contains(compte)) accounts.add(a);
        }
        if (accounts.isEmpty()) {
            cb.onSuccess(ToolResult.text(MailAccountStore.accounts(ctx).isEmpty()
                    ? "Aucune boîte mail configurée : ouvre l'écran Mails pour ajouter ton Gmail."
                    : "Aucune boîte ne correspond à « " + compte + " »."));
            return;
        }
        boolean unreadOnly = !action.equals("list");
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            StringBuilder spoken = new StringBuilder();
            StringBuilder llm = new StringBuilder(unreadOnly
                    ? "Consigne de réponse : cite chaque mail non lu (expéditeur + idée de l'objet "
                    + "ou du début), une phrase courte chacun ; signale ce qui semble important.\n"
                    : "Consigne de réponse : résume les mails les plus récents.\n");
            for (String a : accounts) {
                List<MailMessage> mails;
                String note = "à jour";
                try {
                    mails = GmailImapClient.fetchLatest(a, MailAccountStore.password(app, a),
                            MailActivity.MAX_PER_ACCOUNT_FOR_TOOL);
                    MailCache.save(app, a, mails, System.currentTimeMillis());
                } catch (Exception e) {
                    mails = MailCache.load(app, a);
                    note = "hors ligne (" + MailErrors.describe(e) + ") : dernier relevé";
                }
                describe(a, mails, note, unreadOnly, spoken, llm);
            }
            main.post(() -> cb.onSuccess(ToolResult.text(spoken.toString().trim(),
                    llm.toString().trim())));
        }, "mails-tool").start();
    }

    /** Pur, testable. */
    static void describe(String account, List<MailMessage> mails, String note,
            boolean unreadOnly, StringBuilder spoken, StringBuilder llm) {
        int unread = 0;
        for (MailMessage m : mails) if (!m.seen) unread++;
        spoken.append(account).append(" : ").append(unreadOnly
                ? (unread == 0 ? "rien de non lu" : unread + (unread > 1 ? " non lus" : " non lu"))
                : mails.size() + " mails").append(". ");
        llm.append(account).append(" — ").append(note).append('\n');
        int n = 0;
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE);
        for (MailMessage m : mails) {
            if (unreadOnly && m.seen) continue;
            if (n++ >= MAX_LISTED) break;
            llm.append("- ").append(m.sender());
            if (!m.seen) llm.append(" [non lu]");
            if (m.dateMs > 0) llm.append(" (").append(fmt.format(new Date(m.dateMs))).append(')');
            llm.append(" — objet : ").append(m.subject.isEmpty() ? "(sans objet)" : m.subject);
            if (!m.snippet.isEmpty()) llm.append(" — début : ").append(m.snippet);
            llm.append('\n');
        }
        if (n == 0) llm.append(unreadOnly ? "(aucun mail non lu)\n" : "(boîte vide)\n");
    }
}
