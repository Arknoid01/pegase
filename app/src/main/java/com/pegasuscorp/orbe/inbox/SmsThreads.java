package com.pegasuscorp.orbe.inbox;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.Telephony;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dernières conversations SMS (dernier message, non-lus, nom du contact) pour la Messagerie.
 * Lecture seule ; MMS ignorés.
 */
public final class SmsThreads {

    /** Au-delà, on arrête de parcourir : les non-lus sont récents. */
    private static final int MAX_ROWS = 3000;

    private SmsThreads() {}

    public static boolean canRead(Context ctx) {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static List<InboxItem> latest(Context ctx, int maxThreads) {
        List<InboxItem> out = new ArrayList<>();
        if (!canRead(ctx)) return out;
        ContentResolver cr = ctx.getContentResolver();
        Map<Long, String[]> lastByThread = new LinkedHashMap<>();
        Map<Long, Long> dateByThread = new HashMap<>();
        Map<Long, Integer> unreadByThread = new HashMap<>();
        try (Cursor c = cr.query(Telephony.Sms.CONTENT_URI,
                new String[]{Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                        Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ},
                null, null, Telephony.Sms.DATE + " DESC")) {
            int rows = 0;
            while (c != null && c.moveToNext() && rows++ < MAX_ROWS) {
                long thread = c.getLong(0);
                boolean known = lastByThread.containsKey(thread);
                if (!known) {
                    if (lastByThread.size() >= maxThreads) continue;
                    boolean mine = c.getInt(4) != Telephony.Sms.MESSAGE_TYPE_INBOX;
                    String body = c.getString(2);
                    lastByThread.put(thread, new String[]{c.getString(1),
                            (mine ? "Vous : " : "") + (body != null ? body : "")});
                    dateByThread.put(thread, c.getLong(3));
                }
                if (c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX && c.getInt(5) == 0
                        && lastByThread.containsKey(thread)) {
                    Integer n = unreadByThread.get(thread);
                    unreadByThread.put(thread, n == null ? 1 : n + 1);
                }
            }
        }
        boolean contacts = ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        for (Map.Entry<Long, String[]> e : lastByThread.entrySet()) {
            String address = e.getValue()[0];
            String name = contacts ? contactName(cr, address) : null;
            Integer unread = unreadByThread.get(e.getKey());
            out.add(new InboxItem(InboxItem.Source.SMS,
                    name != null ? name : (address != null ? address : "?"),
                    e.getValue()[1].replaceAll("\\s+", " ").trim(),
                    dateByThread.get(e.getKey()), "", unread != null ? unread : 0, false,
                    address));
        }
        return out;
    }

    private static String contactName(ContentResolver cr, String address) {
        if (address == null || address.trim().isEmpty()) return null;
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address));
        try (Cursor c = cr.query(uri, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                null, null, null)) {
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
