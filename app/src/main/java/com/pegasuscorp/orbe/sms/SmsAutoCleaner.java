package com.pegasuscorp.orbe.sms;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * Ménage tout seul : scan → LLM → suppression uniquement des verdicts « spam ».
 * Les doutes, services et spams seulement probables restent dans l'écran Nettoyer SMS.
 */
public final class SmsAutoCleaner {

    private static final String TAG = "SmsAutoClean";
    static final long COOLDOWN_MS = 6L * 60 * 60 * 1000;
    static final long RETRY_MS = 30L * 60 * 1000;

    private SmsAutoCleaner() {}

    public static void maybeSweepAsync(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        new Thread(() -> maybeSweep(app), "sms-auto-clean").start();
    }

    /** @return nombre de conversations supprimées (0 si rien à faire / cooldown). */
    public static int maybeSweep(Context ctx) {
        if (!SmsInbox.isAutoCleanEnabled(ctx)) return 0;
        long last = SmsInbox.lastAutoSweepMs(ctx);
        if (last > 0 && System.currentTimeMillis() - last < COOLDOWN_MS) return 0;
        return sweep(ctx);
    }

    public static int sweep(Context ctx) {
        if (!SmsInbox.isAutoCleanEnabled(ctx)) return 0;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            return 0;
        }
        if (!SmsInbox.canDeleteViaPegaseSms(ctx) && !SmsInbox.isDefaultSmsApp(ctx)) {
            Log.i(TAG, "pas d'app SMS pour supprimer, reporté");
            return 0;
        }
        List<SmsSpamRules.Candidate> found;
        try {
            found = SmsInbox.scan(ctx);
        } catch (Exception e) {
            Log.w(TAG, "scan failed", e);
            return 0;
        }
        if (found.isEmpty()) {
            SmsInbox.markAutoSweepDone(ctx);
            return 0;
        }
        boolean classified = SmsSpamLlm.classify(ctx, found);
        List<Long> ids = SmsSpamRules.sureSpamThreadIds(found);
        // Tri raté (réseau, réponse illisible) : réessayer au prochain passage, pas dans 6 h.
        if (classified) {
            SmsInbox.markAutoSweepDone(ctx);
        } else {
            SmsInbox.markAutoSweepRetry(ctx, COOLDOWN_MS, RETRY_MS);
        }
        if (ids.isEmpty()) {
            Log.i(TAG, "aucun spam sûr parmi " + found.size());
            return 0;
        }
        int deleted = SmsInbox.canDeleteViaPegaseSms(ctx)
                ? SmsInbox.deleteThreadsViaPegaseSms(ctx, ids)
                : SmsInbox.deleteThreads(ctx, ids);
        Log.i(TAG, "supprimé " + deleted + " / " + ids.size() + " spams sûrs");
        return Math.max(0, deleted);
    }
}
