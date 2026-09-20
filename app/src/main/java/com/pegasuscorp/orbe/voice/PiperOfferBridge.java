package com.pegasuscorp.orbe.voice;

import android.content.Context;

import com.pegasuscorp.orbe.session.HomeLoadPolicy;

/**
 * Offre Piper au 1er TTS (PR4) — évite le dialog au wire assistant.
 */
public final class PiperOfferBridge {

    public interface Offerer {
        void maybeOfferBeforeTts(Runnable onReady);
    }

    private static volatile Offerer offerer;

    private PiperOfferBridge() {}

    public static void register(Offerer o) {
        offerer = o;
    }

    public static void unregister(Offerer o) {
        if (offerer == o) offerer = null;
    }

    /** Piper activé, modèle absent, dialog pas encore proposé. */
    public static boolean shouldGate(Context ctx) {
        if (ctx == null || !HomeLoadPolicy.isLazyBootstrapEnabled(ctx)) return false;
        Context app = ctx.getApplicationContext();
        return PiperModelStore.usePiper(app)
                && !PiperModelStore.isModelReady(app)
                && !PiperModelStore.wasDownloadOffered(app);
    }

    public static void maybeOffer(Context ctx, Runnable onReady) {
        Runnable proceed = onReady != null ? onReady : () -> {};
        if (!shouldGate(ctx)) {
            proceed.run();
            return;
        }
        Offerer o = offerer;
        if (o != null) {
            o.maybeOfferBeforeTts(proceed);
        } else {
            proceed.run();
        }
    }
}
