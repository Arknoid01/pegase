package com.pegasuscorp.orbe.tools.knowledge;

import android.content.Context;

/** @deprecated Utiliser {@link NasaImageHelper#show(Context, String)} */
public final class NasaImageOverlay {

    private NasaImageOverlay() {}

    public static void show(Context context, String reply) {
        NasaImageHelper.show(context, reply);
    }

    public static void hide(Context context) {
        // L'aperçu est une Activity — se ferme via tap ou bouton ✕.
    }
}
