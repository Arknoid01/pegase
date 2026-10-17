package com.pegasuscorp.orbe.inbox;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

/**
 * Ouvre une conversation WhatsApp / Messenger au bon endroit (appui sur une notification
 * Pégase ou sur une ligne de la Messagerie), puis se ferme.
 *
 * 1. Lien de la notification d'origine, s'il est encore en mémoire → la bonne conversation.
 * 2. WhatsApp et nom = numéro → wa.me/numéro.
 * 3. Sinon l'appli.
 */
public class InboxOpenActivity extends Activity {

    private static final String EXTRA_SOURCE = "source";
    private static final String EXTRA_NAME = "name";

    public static Intent intent(Context ctx, InboxItem.Source source, String name) {
        return new Intent(ctx, InboxOpenActivity.class)
                .putExtra(EXTRA_SOURCE, source.name())
                .putExtra(EXTRA_NAME, name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            InboxItem.Source source = InboxItem.Source.valueOf(
                    getIntent().getStringExtra(EXTRA_SOURCE));
            String name = getIntent().getStringExtra(EXTRA_NAME);
            open(this, source, name);
        } catch (Exception ignored) {
        }
        finish();
        overridePendingTransition(0, 0);
    }

    /** Depuis une activité au premier plan (sinon Android bloque le lancement). */
    static void open(Activity from, InboxItem.Source source, String name) {
        String key = NotificationInboxStore.keyOf(source, name);
        NotificationInboxStore.markRead(from, key);
        MessagingNotificationHandler.clearPosted(from, key);
        PendingIntent pi = NotificationInboxStore.openIntent(key);
        if (pi != null) {
            try {
                Bundle opts = null;
                if (Build.VERSION.SDK_INT >= 34) {
                    opts = ActivityOptions.makeBasic()
                            .setPendingIntentBackgroundActivityStartMode(
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                            .toBundle();
                }
                pi.send(from, 0, null, null, null, null, opts);
                return;
            } catch (PendingIntent.CanceledException e) {
                NotificationInboxStore.forgetOpenIntent(key);
            }
        }
        String pkg = source == InboxItem.Source.WHATSAPP ? "com.whatsapp" : "com.facebook.orca";
        // Contact non enregistré : WhatsApp affiche le numéro (« +33 6 12 34 56 78 »).
        if (source == InboxItem.Source.WHATSAPP && name != null
                && name.trim().matches("\\+?[0-9][0-9 .\\-]{7,}")) {
            try {
                from.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://wa.me/" + name.replaceAll("[^0-9]", "")))
                        .setPackage(pkg));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        Intent launch = from.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) from.startActivity(launch);
    }
}
