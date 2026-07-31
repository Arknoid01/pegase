package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.util.Log;

/**
 * Coordination micro / wake word — une seule source de vérité (process launcher).
 *
 * <pre>
 * État accueil idle      → wake ✅, voix ❌, bureau ❌, texte ❌
 * Discussion texte       → wake ❌, voix ❌, bureau ❌, texte ✅ (pas de micro)
 * Chat vocal actif       → wake ❌, voix ✅, bureau ❌, texte ❌
 * Bureau ouvert          → wake ❌, voix ❌, bureau ✅ (push-to-talk)
 * </pre>
 *
 * Les flags static restent dans le process launcher ; le process {@code :voice}
 * ne les lit jamais — start/stop via {@link VoiceWakeClient}.
 */
public final class PegaseWakeController {

    private static final String TAG = "PegaseWake";

    private static volatile boolean voiceChatActive;
    private static volatile boolean textDiscussionActive;
    private static volatile boolean pausedByUser;
    private static volatile boolean micGloballyMuted;
    private static volatile boolean bureauActive;
    private static volatile boolean wakeHealthProblem;

    private PegaseWakeController() {}

    /** Wake word autorisé (STT arrière-plan). */
    public static boolean shouldListen() {
        return !voiceChatActive
                && !textDiscussionActive
                && !pausedByUser
                && !micGloballyMuted
                && !bureauActive;
    }

    /** Le micro partagé ({@code VoiceManager}) doit rester coupé. */
    public static boolean shouldBlockSharedMic() {
        return textDiscussionActive || bureauActive;
    }

    public static void setBureauActive(boolean active) {
        bureauActive = active;
        logState("bureau=" + active);
    }

    public static boolean isBureauActive() {
        return bureauActive;
    }

    public static void setWakeHealthProblem(boolean problem) {
        wakeHealthProblem = problem;
        logState("wakeHealth=" + problem);
    }

    public static boolean hasWakeHealthProblem() {
        return wakeHealthProblem;
    }

    public static void setMicGloballyMuted(boolean muted) {
        micGloballyMuted = muted;
        logState("micMuted=" + muted);
    }

    public static boolean isMicGloballyMuted() {
        return micGloballyMuted;
    }

    public static void setVoiceChatActive(boolean active) {
        voiceChatActive = active;
        logState("voiceChat=" + active);
    }

    public static boolean isVoiceChatActive() {
        return voiceChatActive;
    }

    public static void setTextDiscussionActive(boolean active) {
        textDiscussionActive = active;
        logState("textDiscussion=" + active);
    }

    public static boolean isTextDiscussionActive() {
        return textDiscussionActive;
    }

    /** @deprecated Préférer {@link #setVoiceChatActive} ou {@link #setTextDiscussionActive}. */
    @Deprecated
    public static void setChatActive(boolean active) {
        setVoiceChatActive(active);
    }

    /** @deprecated Préférer {@link #isVoiceChatActive} ou {@link #isTextDiscussionActive}. */
    @Deprecated
    public static boolean isChatActive() {
        return voiceChatActive || textDiscussionActive;
    }

    public static void setPausedByUser(boolean paused) {
        pausedByUser = paused;
        logState("userPaused=" + paused);
    }

    /** Relance le wake (:voice) seulement si aucun mode ne bloque l'écoute. */
    public static void resumeWakeIfAllowed(Context context) {
        if (context == null || !shouldListen()) return;
        VoiceWakeClient.get().startListening(context);
    }

    public static void pauseWake(Context context) {
        if (context != null) VoiceWakeClient.get().stopListening(context);
    }

    private static void logState(String change) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return;
        Log.d(TAG, change + " → listen=" + shouldListen()
                + " blockMic=" + shouldBlockSharedMic());
    }
}
