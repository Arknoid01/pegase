package com.pegasuscorp.orbe.sms;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.provider.Telephony;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Accès à la boîte SMS du téléphone pour le ménage.
 *
 * Lire = permission READ_SMS. Supprimer = il faut être l'app SMS PAR DÉFAUT
 * (règle Android) : Pégase le devient le temps du ménage, puis rend la main.
 */
public final class SmsInbox {

    private static final String TAG = "SmsCleaner";
    private static final String PREFS = "sms_cleaner";
    private static final String KEY_PREVIOUS_DEFAULT = "previous_default_sms";
    private static final String KEY_AUTO_SURE = "auto_sure_spam";
    private static final String KEY_LAST_SWEEP_MS = "last_auto_sweep_ms";

    private SmsInbox() {}

    /** Conversations à un seul SMS, reçu, d'un expéditeur hors contacts. */
    public static List<SmsSpamRules.Candidate> scan(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        Map<Long, Integer> countByThread = new HashMap<>();
        Map<Long, SmsSpamRules.Candidate> firstInbox = new HashMap<>();

        try (Cursor c = cr.query(Telephony.Sms.CONTENT_URI,
                new String[]{Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE},
                null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    long thread = c.getLong(0);
                    Integer n = countByThread.get(thread);
                    countByThread.put(thread, n == null ? 1 : n + 1);
                    if (c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX
                            && !firstInbox.containsKey(thread)) {
                        firstInbox.put(thread, new SmsSpamRules.Candidate(
                                thread, c.getString(1), c.getString(2), c.getLong(3)));
                    }
                }
            }
        }

        // Une conversation qui contient un MMS n'est pas « un seul SMS ».
        Set<Long> mmsThreads = new HashSet<>();
        try (Cursor c = cr.query(Telephony.Mms.CONTENT_URI,
                new String[]{Telephony.Mms.THREAD_ID}, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) mmsThreads.add(c.getLong(0));
            }
        } catch (Exception ignored) {
            // Certains constructeurs refusent la lecture MMS : on fait sans.
        }

        boolean canReadContacts = ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        List<SmsSpamRules.Candidate> out = new ArrayList<>();
        for (Map.Entry<Long, SmsSpamRules.Candidate> e : firstInbox.entrySet()) {
            long thread = e.getKey();
            if (countByThread.get(thread) != 1 || mmsThreads.contains(thread)) continue;
            SmsSpamRules.Candidate cand = e.getValue();
            if (canReadContacts && isKnownContact(cr, cand.address)) continue;
            SmsSpamRules.classify(cand);
            out.add(cand);
        }
        out.sort((a, b) -> Long.compare(b.date, a.date));
        return out;
    }

    private static boolean isKnownContact(ContentResolver cr, String address) {
        if (address == null || address.trim().isEmpty()) return false;
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address));
        try (Cursor c = cr.query(uri, new String[]{ContactsContract.PhoneLookup._ID},
                null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    /** Supprime les conversations ; renvoie le nombre supprimé. Exige d'être l'app SMS par défaut. */
    public static int deleteThreads(Context ctx, List<Long> threadIds) {
        int deleted = 0;
        for (long id : threadIds) {
            try {
                Uri uri = ContentUris.withAppendedId(Telephony.Threads.CONTENT_URI, id);
                int n = ctx.getContentResolver().delete(uri, null, null);
                Log.i(TAG, "delete thread " + id + " -> " + n);
                if (n > 0) deleted++;
            } catch (Exception e) {
                Log.w(TAG, "delete thread " + id + " failed", e);
            }
        }
        return deleted;
    }

    private static final String PEGASE_SMS_PKG = "com.pegasuscorp.sms";
    private static final Uri PEGASE_SMS_DELETE = Uri.parse("content://com.pegasuscorp.sms.delete");

    /**
     * Pégase SMS est l'app SMS par défaut et accepte nos demandes de suppression :
     * le ménage se fait sans changer d'app par défaut.
     */
    public static boolean canDeleteViaPegaseSms(Context ctx) {
        if (!PEGASE_SMS_PKG.equals(Telephony.Sms.getDefaultSmsPackage(ctx))) return false;
        if (ctx.checkSelfPermission("com.pegasuscorp.sms.permission.DELETE_THREADS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        return ctx.getPackageManager().resolveContentProvider(
                PEGASE_SMS_DELETE.getAuthority(), 0) != null;
    }

    /**
     * Cherche le fil à 1 SMS de cet expéditeur (celui qu'on vient de classer)
     * et le supprime si on peut. Renvoie le nombre de fils supprimés.
     */
    public static int deleteSureSpamByAddress(Context ctx, String address) {
        if (address == null || address.trim().isEmpty()) return 0;
        if (!canDeleteViaPegaseSms(ctx) && !isDefaultSmsApp(ctx)) return 0;
        List<SmsSpamRules.Candidate> found;
        try {
            found = scan(ctx);
        } catch (Exception e) {
            Log.w(TAG, "scan for auto-delete failed", e);
            return 0;
        }
        List<Long> ids = new ArrayList<>();
        for (SmsSpamRules.Candidate c : found) {
            if (address.equals(c.address)) ids.add(c.threadId);
        }
        if (ids.isEmpty()) return 0;
        return canDeleteViaPegaseSms(ctx)
                ? deleteThreadsViaPegaseSms(ctx, ids)
                : deleteThreads(ctx, ids);
    }

    /** Suppression faite par Pégase SMS (base Android + sa propre liste) ; -1 si refusée. */
    public static int deleteThreadsViaPegaseSms(Context ctx, List<Long> threadIds) {
        long[] ids = new long[threadIds.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = threadIds.get(i);
        android.os.Bundle extras = new android.os.Bundle();
        extras.putLongArray("thread_ids", ids);
        try {
            android.os.Bundle out = ctx.getContentResolver().call(
                    PEGASE_SMS_DELETE, "delete_threads", null, extras);
            int n = out != null ? out.getInt("deleted", -1) : -1;
            Log.i(TAG, "Pégase SMS delete " + ids.length + " -> " + n);
            return n;
        } catch (Exception e) {
            Log.w(TAG, "Pégase SMS delete failed", e);
            return -1;
        }
    }

    public static boolean isDefaultSmsApp(Context ctx) {
        // Android 10+ : le rôle fait foi. getDefaultSmsPackage() peut rester sur
        // l'ancienne valeur (vu sur Nothing Phone : réglage secure à null).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = ctx.getSystemService(RoleManager.class);
            if (rm != null) return rm.isRoleHeld(RoleManager.ROLE_SMS);
        }
        return ctx.getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(ctx));
    }

    /** Mémorise l'app SMS actuelle (Google Messages) avant de prendre sa place. */
    public static void rememberPreviousDefault(Context ctx) {
        String current = Telephony.Sms.getDefaultSmsPackage(ctx);
        if (current == null || current.equals(ctx.getPackageName())) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PREVIOUS_DEFAULT, current).apply();
    }

    public static String previousDefault(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PREVIOUS_DEFAULT, null);
    }

    /** Demande à Android de faire de Pégase l'app SMS par défaut. */
    public static Intent requestDefaultIntent(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = ctx.getSystemService(RoleManager.class);
            return rm.createRequestRoleIntent(RoleManager.ROLE_SMS);
        }
        return new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, ctx.getPackageName());
    }

    /**
     * Rendre la main à l'ancienne app SMS. Depuis Android 10, une app ne peut pas
     * désigner une AUTRE app : on ouvre le réglage « Applications par défaut ».
     */
    /** Ménage automatique des spams dont le LLM est sûr (défaut : oui). */
    public static boolean isAutoCleanEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_SURE, true);
    }

    public static void setAutoCleanEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_SURE, enabled).apply();
    }

    public static long lastAutoSweepMs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_SWEEP_MS, 0L);
    }

    public static void markAutoSweepDone(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_SWEEP_MS, System.currentTimeMillis()).apply();
    }

    /** Tri raté : antidate le dernier passage pour réessayer dans {@code retryInMs}. */
    public static void markAutoSweepRetry(Context ctx, long cooldownMs, long retryInMs) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_SWEEP_MS,
                        System.currentTimeMillis() - cooldownMs + retryInMs).apply();
    }

    public static Intent restoreDefaultIntent(Context ctx) {
        String previous = previousDefault(ctx);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && previous != null) {
            return new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                    .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, previous);
        }
        return new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
    }
}
