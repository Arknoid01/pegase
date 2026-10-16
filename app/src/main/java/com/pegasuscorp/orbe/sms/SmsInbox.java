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
    public static Intent restoreDefaultIntent(Context ctx) {
        String previous = previousDefault(ctx);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && previous != null) {
            return new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                    .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, previous);
        }
        return new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
    }
}
