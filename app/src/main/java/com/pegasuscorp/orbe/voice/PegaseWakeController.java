package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.util.Log;

/**
 * Coordination micro / wake word — une seule source de vérité (process launcher).
 *
 * <pre>
 * État accueil idle      → wake ✅, voix ❌, bureau ❌, texte ❌
 * Discussion texte       → wake ❌, voix ❌, bureau ❌, texte ✅ (PTT autorisé)
 * Chat vocal actif       → wake ❌, voix ✅, bureau ❌, texte ❌
 * In-place (overlay)     → wake ❌, voix ✅, HOME pas au premier plan
 * Bureau ouvert          → wake ❌, voix ❌, bureau ✅ (push-to-talk)
 * </pre>
 *
 * Les flags static restent dans le process launcher ; le process {@code :voice}
 * ne les lit jamais — start/stop via {@link VoiceWakeClient}.
 *
 * L'état visuel écoute/réflexion est propagé via {@link PegaseVisualStateHub}.
 */
public final class PegaseWakeController {

    private static final String TAG = "PegaseWake";

    private static volatile boolean voiceChatActive;
    private static volatile boolean textDiscussionActive;
    private static volatile boolean pausedByUser;
    private static volatile boolean micGloballyMuted;
    private static volatile boolean bureauActive;
    private static volatile boolean pushToTalkActive;
    private static volatile boolean inPlaceVoiceActive;
    private static volatile boolean micListening;
    private static volatile boolean assistantThinking;
    private static volatile boolean wakeHealthProblem;

    private PegaseWakeController() {}

    /** Wake word autorisé (STT arrière-plan). */
    public static boolean shouldListen() {
        return !voiceChatActive
                && !textDiscussionActive
                && !inPlaceVoiceActive
                && !pausedByUser
                && !micGloballyMuted
                && !bureauActive
                && !pushToTalkActive;
    }

    /** Le micro partagé ({@code VoiceManager}) doit rester coupé. */
    public static boolean shouldBlockSharedMic() {
        if (pushToTalkActive || inPlaceVoiceActive || voiceChatActive) return false;
        return textDiscussionActive || bureauActive;
    }

    public static void setPushToTalkActive(boolean active) {
        pushToTalkActive = active;
        logState("ptt=" + active);
    }

    public static boolean isPushToTalkActive() {
        return pushToTalkActive;
    }

    public static void setInPlaceVoiceActive(boolean active) {
        inPlaceVoiceActive = active;
        if (active) voiceChatActive = true;
        else if (!isVoiceChatSessionOnHome()) voiceChatActive = false;
        logState("inPlace=" + active);
    }

    public static boolean isInPlaceVoiceActive() {
        return inPlaceVoiceActive;
    }

    /** Chat vocal classique sur HOME (pas in-place). */
    public static boolean isVoiceChatSessionOnHome() {
        return voiceChatActive && !inPlaceVoiceActive;
    }

    public static void setMicListening(boolean listening) {
        micListening = listening;
        PegaseVisualStateHub.refresh();
        logState("micListen=" + listening);
    }

    public static boolean isMicListening() {
        return micListening;
    }

    public static void setAssistantThinking(boolean thinking) {
        assistantThinking = thinking;
        PegaseVisualStateHub.refresh();
        logState("thinking=" + thinking);
    }

    public static boolean isAssistantThinking() {
        return assistantThinking;
    }

    /** Tests uniquement — sans Log/Looper Android. */
    static void resetVisualFlagsForTests() {
        micListening = false;
        assistantThinking = false;
    }

    public static void setWakeHealthProblem(boolean problem) {
        wakeHealthProblem = problem;
        logState("wakeHealth=" + problem);
    }

    public static boolean hasWakeHealthProblem() {
        return wakeHealthProblem;
    }

    public static void setBureauActive(boolean active) {
        bureauActive = active;
        logState("bureau=" + active);
    }

    public static boolean isBureauActive() {
        return bureauActive;
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
        if (!active) inPlaceVoiceActive = false;
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

    @Deprecated
    public static void setChatActive(boolean active) {
        setVoiceChatActive(active);
    }

    @Deprecated
    public static boolean isChatActive() {
        return voiceChatActive || textDiscussionActive;
    }

    public static void setPausedByUser(boolean paused) {
        pausedByUser = paused;
        logState("userPaused=" + paused);
    }

    public static void resumeWakeIfAllowed(Context context) {
        if (context == null || !shouldListen()) return;
        VoiceWakeClient.get().startListening(context);
    }

    public static void pauseWake(Context context) {
        if (context != null) VoiceWakeClient.get().stopListening(context);
    }

    private static void logState(String change) {
        try {
            if (!Log.isLoggable(TAG, Log.DEBUG)) return;
            Log.d(TAG, change + " → listen=" + shouldListen()
                    + " blockMic=" + shouldBlockSharedMic()
                    + " phase=" + PegaseVisualStateHub.derivePhase());
        } catch (RuntimeException ignored) {
            // JVM unit tests: android.util.Log not mocked
        }
    }
}
