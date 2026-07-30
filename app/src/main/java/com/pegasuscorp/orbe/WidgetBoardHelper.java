package com.pegasuscorp.orbe;

import android.content.Context;

import java.util.List;

/**
 * Opérations sur l'écran widgets (réinitialisation, etc.).
 */
public final class WidgetBoardHelper {

    public static final int HOST_ID = 2048;

    private WidgetBoardHelper() {}

    /** Supprime tous les widgets enregistrés et leurs liaisons système. */
    public static void resetAll(Context context) {
        List<WidgetStore.Entry> entries = WidgetStore.load(context);
        OrbeAppWidgetHost host = new OrbeAppWidgetHost(context.getApplicationContext(), HOST_ID);
        try {
            host.startListening();
            for (WidgetStore.Entry entry : entries) {
                host.deleteAppWidgetId(entry.appWidgetId);
            }
        } catch (Exception ignored) {
        } finally {
            host.stopListening();
        }
        WidgetStore.clearAll(context);
    }
}
