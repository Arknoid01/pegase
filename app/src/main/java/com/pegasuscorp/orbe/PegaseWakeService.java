package com.pegasuscorp.orbe;

import android.content.Context;

import com.pegasuscorp.orbe.voice.PegaseWakeStore;
import com.pegasuscorp.orbe.voice.VoiceWakeClient;

/**
 * Façade compat — l'ancien {@code Service} wake a été remplacé par
 * {@link com.pegasuscorp.orbe.voice.VoiceService} dans le process {@code :voice}.
 * Les call-sites existants passent par {@link VoiceWakeClient}.
 */
public final class PegaseWakeService {

    private PegaseWakeService() {}

    public static void sync(Context ctx) {
        VoiceWakeClient.get().sync(ctx);
    }

    public static void start(Context ctx) {
        if (!PegaseWakeStore.isEnabled(ctx)) return;
        VoiceWakeClient.get().startListening(ctx);
    }

    public static void stop(Context ctx) {
        VoiceWakeClient.get().stopListening(ctx);
    }

    public static void pause(Context ctx) {
        // Garder SCO pendant la conversation / STT (sinon micro casque coupé → error 7).
        VoiceWakeClient.get().pauseKeepSco(ctx);
    }

    public static void resume(Context ctx) {
        if (!PegaseWakeStore.isEnabled(ctx)) return;
        VoiceWakeClient.get().startListening(ctx);
    }

    /** @deprecated le service vit dans {@code :voice} — toujours « distant ». */
    @Deprecated
    public static boolean isRunning() {
        return false;
    }
}
