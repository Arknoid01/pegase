package com.pegasuscorp.orbe.inbox;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * Rangement des conversations de la Messagerie : Perso (défaut) ou Pro, choisi par
 * l'utilisateur (appui long sur une conversation).
 */
public final class InboxCategoryStore {

    public enum Category { PERSO, PRO }

    private static final String PREFS = "pegase_inbox";
    private static final String KEY_PRO = "pro_conversations";

    private InboxCategoryStore() {}

    /**
     * Clé stable d'une conversation : numéro pour les SMS (le nom du contact peut changer),
     * nom affiché pour WhatsApp / Messenger.
     */
    public static String keyOf(InboxItem it) {
        if (it.source == InboxItem.Source.SMS) {
            String digits = it.address.replaceAll("[^0-9]", "");
            // +33 6… et 06… : même numéro.
            if (digits.startsWith("33") && digits.length() == 11) digits = "0" + digits.substring(2);
            return "SMS|" + (digits.isEmpty() ? it.address : digits);
        }
        return NotificationInboxStore.keyOf(it.source, it.name);
    }

    public static Category get(Context ctx, InboxItem it) {
        return pro(ctx).contains(keyOf(it)) ? Category.PRO : Category.PERSO;
    }

    public static void set(Context ctx, InboxItem it, Category c) {
        Set<String> s = new HashSet<>(pro(ctx));
        if (c == Category.PRO) s.add(keyOf(it));
        else s.remove(keyOf(it));
        prefs(ctx).edit().putStringSet(KEY_PRO, s).apply();
    }

    private static Set<String> pro(Context ctx) {
        return prefs(ctx).getStringSet(KEY_PRO, new HashSet<>());
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
