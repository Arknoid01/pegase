package com.pegasuscorp.orbe.sms;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.pegasuscorp.orbe.llm.LocalLlmServer;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ménage tout seul : scan → LLM → suppression uniquement des verdicts « spam ».
 * Les doutes, services et spams seulement probables restent dans l'écran Nettoyer SMS.
 */
public final class SmsAutoCleaner {

    private static final String TAG = "SmsAutoClean";
    static final long COOLDOWN_MS = 6L * 60 * 60 * 1000;
    static final long RETRY_MS = 30L * 60 * 1000;
    /** Modèle local : chaque essai occupe le GPU ~1 min, on espace davantage. */
    static final long RETRY_LOCAL_MS = 2L * 60 * 60 * 1000;
    /** Un seul tri à la fois : chaque retour à l'accueil relançait un tri pendant le précédent. */
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private SmsAutoCleaner() {}

    public static void maybeSweepAsync(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        // Modèle local : le tri occupe Gemma ~1 min et ferait attendre une discussion.
        // On le garde pour quand l'écran s'éteint (choix de Yannick, 2026-11-27).
        if (LocalLlmServer.isLocalOnly(app) && screenOn(app)) {
            Log.i(TAG, "écran allumé, tri reporté à l'extinction");
            return;
        }
        new Thread(() -> maybeSweep(app), "sms-auto-clean").start();
    }

    private static boolean screenOn(Context ctx) {
        android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private static volatile boolean screenOffRegistered = false;

    /** Lance le tri (s'il est dû) à chaque extinction de l'écran. */
    public static synchronized void registerScreenOff(Context ctx) {
        if (ctx == null || screenOffRegistered) return;
        Context app = ctx.getApplicationContext();
        try {
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context c, android.content.Intent i) {
                    maybeSweepAsync(app);
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(r, f);
            }
            screenOffRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "écoute écran éteint impossible", e);
        }
    }

    /** @return nombre de conversations supprimées (0 si rien à faire / cooldown). */
    public static int maybeSweep(Context ctx) {
        if (!SmsInbox.isAutoCleanEnabled(ctx)) return 0;
        long last = SmsInbox.lastAutoSweepMs(ctx);
        if (last > 0 && System.currentTimeMillis() - last < COOLDOWN_MS) return 0;
        return sweep(ctx);
    }

    public static int sweep(Context ctx) {
        if (!RUNNING.compareAndSet(false, true)) {
            Log.i(TAG, "tri déjà en cours, ignoré");
            return 0;
        }
        try {
            return sweepLocked(ctx);
        } finally {
            RUNNING.set(false);
        }
    }

    private static int sweepLocked(Context ctx) {
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
        long retryMs = LocalLlmServer.isLocalOnly(ctx) ? RETRY_LOCAL_MS : RETRY_MS;
        // Posé AVANT l'appel : si l'appli meurt pendant le tri, pas de relance immédiate.
        SmsInbox.markAutoSweepRetry(ctx, COOLDOWN_MS, retryMs);
        boolean classified = SmsSpamLlm.classify(ctx, found);
        List<Long> ids = SmsSpamRules.sureSpamThreadIds(found);
        // Tri raté (réseau, réponse illisible) : réessayer au prochain passage, pas dans 6 h.
        if (classified) {
            SmsInbox.markAutoSweepDone(ctx);
        } else {
            SmsInbox.markAutoSweepRetry(ctx, COOLDOWN_MS, retryMs);
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
