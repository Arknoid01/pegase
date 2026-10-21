package com.pegasuscorp.orbe.inbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;

import com.pegasuscorp.orbe.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Notifications de messages WhatsApp / Messenger : Pégase les note dans la Messagerie,
 * affiche la sienne et efface celle de l'appli.
 *
 * Sûr par construction : c'est Pégase qui efface l'originale, après avoir posté la sienne.
 * S'il ne tourne pas, rien n'est effacé et la notification de l'appli reste.
 * Appels, notifications permanentes et résumés de groupe vides ne sont pas touchés.
 */
public final class MessagingNotificationHandler {

    private static final String TAG = "PegaseInbox";
    private static final String CHANNEL = "pegase_messagerie";
    private static final String PREFS = "pegase_inbox";
    private static final String KEY_TAKE_OVER = "take_over_notifications";
    private static final String PKG_WHATSAPP = "com.whatsapp";
    private static final String PKG_MESSENGER = "com.facebook.orca";
    /** {@code Notification.CATEGORY_MISSED_CALL} (API 31). */
    private static final String CATEGORY_MISSED_CALL = "missed_call";

    private MessagingNotificationHandler() {}

    /** Pégase remplace les notifications de messages WhatsApp / Messenger (défaut : oui). */
    public static boolean takesOver(Context ctx) {
        return prefs(ctx).getBoolean(KEY_TAKE_OVER, true);
    }

    public static void setTakesOver(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_TAKE_OVER, on).apply();
    }

    public static InboxItem.Source sourceOf(String pkg) {
        if (PKG_WHATSAPP.equals(pkg)) return InboxItem.Source.WHATSAPP;
        if (PKG_MESSENGER.equals(pkg)) return InboxItem.Source.MESSENGER;
        return null;
    }

    /** Appelé par le service d'écoute pour chaque notification postée. */
    public static void onPosted(NotificationListenerService listener, StatusBarNotification sbn) {
        InboxItem.Source source = sourceOf(sbn.getPackageName());
        if (source == null) source = debugTestSource(listener, sbn);
        if (source == null) return;
        Notification n = sbn.getNotification();
        if (n == null) return;
        if (Notification.CATEGORY_CALL.equals(n.category)
                || CATEGORY_MISSED_CALL.equals(n.category)
                || (n.flags & Notification.FLAG_ONGOING_EVENT) != 0
                || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            return;
        }
        Context ctx = listener.getApplicationContext();
        boolean summary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        if (summary) {
            // « 5 messages dans 2 discussions » : redondant avec les nôtres.
            if (takesOver(ctx)) cancel(listener, sbn);
            return;
        }
        Parsed p = parse(n);
        if (p == null) return;
        boolean fresh = NotificationInboxStore.record(ctx, source, p.title, p.group, p.messages,
                n.contentIntent);
        if (!takesOver(ctx)) return;
        if (fresh) {
            try {
                post(ctx, source, NotificationInboxStore.get(ctx,
                        NotificationInboxStore.keyOf(source, p.title)));
            } catch (Exception e) {
                // Notification Pégase impossible : on laisse l'originale.
                Log.w(TAG, "post failed", e);
                return;
            }
        }
        cancel(listener, sbn);
    }

    /**
     * Version de développement seulement : une notification postée par ADB
     * ({@code cmd notification post}, paquet com.android.shell) avec l'étiquette
     * {@code pegase-test-wa…} / {@code pegase-test-ms…} est traitée comme WhatsApp / Messenger.
     */
    private static InboxItem.Source debugTestSource(Context ctx, StatusBarNotification sbn) {
        boolean debuggable = (ctx.getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!debuggable || !"com.android.shell".equals(sbn.getPackageName())) return null;
        String tag = sbn.getTag() != null ? sbn.getTag() : "";
        if (tag.startsWith("pegase-test-wa")) return InboxItem.Source.WHATSAPP;
        if (tag.startsWith("pegase-test-ms")) return InboxItem.Source.MESSENGER;
        return null;
    }

    // ── Lecture de la notification ───────────────────────────────────────

    static final class Parsed {
        final String title;
        final boolean group;
        final List<NotificationInboxStore.Message> messages;

        Parsed(String title, boolean group, List<NotificationInboxStore.Message> messages) {
            this.title = title;
            this.group = group;
            this.messages = messages;
        }
    }

    private static Parsed parse(Notification n) {
        NotificationCompat.MessagingStyle style =
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n);
        if (style != null && !style.getMessages().isEmpty()) {
            CharSequence conv = style.getConversationTitle();
            // Une discussion à deux n'a pas de titre de conversation : un titre = un groupe.
            boolean group = style.isGroupConversation()
                    || (conv != null && conv.toString().trim().length() > 0);
            List<NotificationInboxStore.Message> out = new ArrayList<>();
            String firstSender = null;
            for (NotificationCompat.MessagingStyle.Message m : style.getMessages()) {
                Person who = m.getPerson();
                // Pas d'expéditeur = message envoyé par l'utilisateur : pas un non-lu.
                if (who == null || who.getName() == null) continue;
                String sender = who.getName().toString();
                if (firstSender == null) firstSender = sender;
                CharSequence text = m.getText();
                out.add(new NotificationInboxStore.Message(group ? sender : "",
                        text != null ? text.toString() : "", m.getTimestamp()));
            }
            String title = conv != null ? conv.toString() : firstSender;
            if (title == null) title = text(n, Notification.EXTRA_TITLE);
            return out.isEmpty() || title == null ? null : new Parsed(clean(title), group, out);
        }
        // Sans MessagingStyle, seule une notification marquée « message » en est un :
        // « Connexion récente », « Sauvegarde impossible », « Appel manqué »… restent à l'appli.
        if (!Notification.CATEGORY_MESSAGE.equals(n.category)) return null;
        if (isMissedCallText(text(n, Notification.EXTRA_TEXT))) return null;
        String title = text(n, Notification.EXTRA_TITLE);
        String body = text(n, Notification.EXTRA_TEXT);
        if (title == null || body == null) return null;
        List<NotificationInboxStore.Message> one = new ArrayList<>();
        one.add(new NotificationInboxStore.Message("", body, n.when > 0 ? n.when
                : System.currentTimeMillis()));
        return new Parsed(clean(title), false, one);
    }

    /** WhatsApp ajoute « (3 messages) » au titre des discussions groupées. */
    static String clean(String title) {
        return NotificationInboxStore.stripInvisible(title).replaceAll("\\s*\\(\\d+ (messages?|nouveaux messages)\\)\\s*$", "").trim();
    }

    /** Messenger range parfois l'appel manqué en catégorie « message ». */
    static boolean isMissedCallText(String body) {
        if (body == null) return false;
        String b = body.toLowerCase(java.util.Locale.FRANCE);
        return b.contains("appel manqué") || b.contains("manqué un appel")
                || b.contains("missed call") || b.contains("missed a call");
    }

    private static String text(Notification n, String key) {
        CharSequence cs = n.extras != null ? n.extras.getCharSequence(key) : null;
        return cs != null && cs.toString().trim().length() > 0 ? cs.toString().trim() : null;
    }

    // ── Notification Pégase ──────────────────────────────────────────────

    private static void post(Context ctx, InboxItem.Source source,
            NotificationInboxStore.Conversation c) {
        if (c == null) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Messagerie",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Messages WhatsApp et Messenger, relayés par Pégase");
            nm.createNotificationChannel(ch);
        }
        String app = source == InboxItem.Source.WHATSAPP ? "WhatsApp" : "Messenger";
        Person me = new Person.Builder().setName("Moi").build();
        NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(me);
        style.setGroupConversation(c.group);
        style.setConversationTitle(c.group ? c.name + " · " + app : app);
        int shown = 0;
        List<NotificationInboxStore.Message> msgs = c.messages;
        int from = Math.max(0, msgs.size() - Math.max(1, Math.min(c.unread, 6)));
        for (int i = from; i < msgs.size(); i++) {
            NotificationInboxStore.Message m = msgs.get(i);
            Person who = new Person.Builder()
                    .setName(m.sender.isEmpty() ? c.name : m.sender).build();
            style.addMessage(m.text, m.timeMs, who);
            shown++;
        }
        if (shown == 0) return;
        int id = c.key().hashCode();
        Intent open = InboxOpenActivity.intent(ctx, source, c.name);
        PendingIntent pi = PendingIntent.getActivity(ctx, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification out = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_pegase)
                .setStyle(style)
                .setSubText(app)
                .setContentTitle(c.name)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setNumber(Math.max(0, c.unread))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .build();
        nm.notify(id, out);
    }

    /** Efface notre notification d'une conversation (ouverte depuis la Messagerie). */
    public static void clearPosted(Context ctx, String key) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(key.hashCode());
    }

    private static void cancel(NotificationListenerService listener, StatusBarNotification sbn) {
        try {
            listener.cancelNotification(sbn.getKey());
        } catch (Exception e) {
            Log.w(TAG, "cancel failed", e);
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
