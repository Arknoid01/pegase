package com.pegasuscorp.orbe.intentions.location;

import android.content.Context;

import com.pegasuscorp.orbe.permissions.PermissionFlow;
import com.pegasuscorp.orbe.session.HomeLoadPolicy;

/**
 * Localisation à la demande (PR4) — pas de dialog GPS au boot HOME.
 */
public final class LocationLazyGate {

    private LocationLazyGate() {}

    public static boolean deferAtBoot(Context ctx) {
        return HomeLoadPolicy.deferLocationAtBoot(ctx);
    }

    /** Écoute passive si permission déjà accordée — sans dialog. */
    public static void bootstrapIfPermitted(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        if (LocationSituationBootstrap.hasLocationPermission(app)) {
            LocationSituationBootstrap.ensureStarted(app);
        }
    }

    /**
     * Demande runtime si nécessaire (Activity) puis bootstrap.
     * À appeler depuis réglages situations / outil météo explicite.
     */
    public static void requestWhenNeeded(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        if (PermissionFlow.hasLocation(app)) {
            LocationSituationBootstrap.ensureStarted(app);
            return;
        }
        PermissionFlow.ensureLocation(ctx);
    }

    /** Prefetch / intentions / météo — ne jamais afficher le dialog ici. */
    public static void warmForBackgroundUse(Context ctx) {
        bootstrapIfPermitted(ctx);
    }
}
