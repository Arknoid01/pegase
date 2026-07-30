package com.pegasuscorp.orbe.chat;

import android.content.Context;
import android.content.Intent;

import com.pegasuscorp.orbe.MainActivity;
import com.pegasuscorp.orbe.FloatingOrbService;
import com.pegasuscorp.orbe.PegaseInterfaceState;
import com.pegasuscorp.orbe.PegaseWakeService;
import com.pegasuscorp.orbe.bureau.BureauHost;
import com.pegasuscorp.orbe.voice.AssistantVolumeGuard;
import com.pegasuscorp.orbe.voice.PegaseWakeController;
import com.pegasuscorp.orbe.voice.VoiceSessionContext;
import com.pegasuscorp.orbe.voice.VoiceManager;

import java.lang.ref.WeakReference;

/**
 * Relais vocal partagé : un seul {@link VoiceManager}, session conservée si le launcher
 * est détruit pendant que l'interface Pégase reste ouverte.
 */
public final class ChatVoiceBridge {

    public static final String EXTRA_PENDING_TRANSCRIPT = "pending_transcript";

    private static WeakReference<MainActivity> host;
    private static WeakReference<BureauHost> bureauHost;
    private static Context appContext;
    private static VoiceManager sharedVoice;
    private static boolean interfaceTakingMic;
    private static boolean sessionPreserved;

    private ChatVoiceBridge() {}

    public static void register(MainActivity activity) {
        host = new WeakReference<>(activity);
        sessionPreserved = false;
        VoiceManager voice = getSharedVoice(activity);
        voice.attachHost(activity);
    }

    public static void unregister(MainActivity activity) {
        if (host != null && host.get() == activity) {
            host = null;
        }
        VoiceManager voice = sharedVoice;
        if (voice != null) {
            voice.detachHost(activity);
        }
    }

    public static void registerBureau(BureauHost activity) {
        bureauHost = new WeakReference<>(activity);
        if (activity instanceof android.app.Activity) {
            android.app.Activity a = (android.app.Activity) activity;
            PegaseWakeController.setBureauActive(true);
            PegaseWakeController.pauseWake(a);
        }
        VoiceManager voice = sharedVoice;
        if (voice != null) {
            voice.cancelScheduledListening();
            voice.stopListening();
        }
    }

    public static void unregisterBureau(BureauHost activity) {
        if (bureauHost != null && bureauHost.get() == activity) {
            bureauHost = null;
        }
        PegaseWakeController.setBureauActive(false);
        if (activity instanceof android.content.Context) {
            PegaseWakeController.resumeWakeIfAllowed((android.content.Context) activity);
        }
    }

    public static boolean isBureauActive() {
        return bureauHost != null && bureauHost.get() != null;
    }

    public static synchronized VoiceManager getSharedVoice(Context context) {
        if (appContext == null) appContext = context.getApplicationContext();
        if (sharedVoice == null) {
            sharedVoice = new VoiceManager(appContext, ChatVoiceBridge::deliverTranscript);
        }
        return sharedVoice;
    }

    public static void reloadSharedPiper() {
        if (sharedVoice != null) {
            sharedVoice.reloadPiperModel();
            sharedVoice.applyAndroidTtsSettings();
        }
    }

    public static void markSessionPreserved(boolean preserved) {
        sessionPreserved = preserved;
    }

    public static boolean isSessionPreserved() {
        return sessionPreserved;
    }

    public static void releaseSharedVoiceIfIdle() {
        if (sharedVoice == null) return;
        if (interfaceTakingMic) return;
        if (isBureauActive()) return;
        if (ChatSessionRegistry.isActive()) return;
        if (host != null && host.get() != null) return;
        sharedVoice.release();
        sharedVoice = null;
    }

    public static void onInterfaceOpening() {
        interfaceTakingMic = true;
        MainActivity a = host != null ? host.get() : null;
        if (a != null) a.pauseVoiceForInterface();
    }

    public static void onInterfaceClosed() {
        interfaceTakingMic = false;
        Context ctx = appContext;
        PegaseWakeController.setTextDiscussionActive(false);

        // Sortie propre de l'UI texte : terminer la session sauf chat vocal encore actif.
        // Sinon ConversationManager.active reste true → orbe de superposition au prochain onPause.
        if (!PegaseWakeController.isVoiceChatActive()) {
            try {
                ChatSessionRegistry.finalizeSession();
            } catch (Exception ignored) {
            }
            try {
                VoiceSessionContext.get().clear();
            } catch (Exception ignored) {
            }
            if (ctx != null) {
                FloatingOrbService.hide(ctx);
            }
            sessionPreserved = false;
        }

        MainActivity a = host != null ? host.get() : null;
        if (a != null) {
            a.resumeVoiceAfterInterface();
            return;
        }
        if (sessionPreserved) {
            finalizePreservedSession();
            return;
        }
        if (ctx != null) {
            PegaseWakeController.resumeWakeIfAllowed(ctx);
        }
        releaseSharedVoiceIfIdle();
    }

    /** Termine proprement une session conservée sans MainActivity. */
    public static void finalizePreservedSession() {
        sessionPreserved = false;
        if (appContext != null) {
            boolean saved = ChatSessionRegistry.finalizeSession();
            if (saved) {
                android.util.Log.d("ChatVoiceBridge", "Mémoire mise à jour (session préservée)");
            }
            VoiceSessionContext.get().clear();
            PegaseWakeController.setVoiceChatActive(false);
            AssistantVolumeGuard.deactivate(appContext);
            PegaseWakeController.resumeWakeIfAllowed(appContext);
        }
        releaseSharedVoiceIfIdle();
    }

    public static boolean isChatActive() {
        if (ChatSessionRegistry.isActive()) return true;
        MainActivity a = host != null ? host.get() : null;
        return a != null && a.isChatActiveForBridge();
    }

    public static boolean isInterfaceTakingMic() {
        return interfaceTakingMic;
    }

    public static void deliverTranscript(String transcript) {
        if (transcript == null || transcript.trim().isEmpty()) return;
        String trimmed = transcript.trim();
        BureauHost bureau = bureauHost != null ? bureauHost.get() : null;
        if (bureau != null) {
            bureau.runOnUiThread(() -> bureau.handleBureauVoice(trimmed));
            return;
        }
        MainActivity a = host != null ? host.get() : null;
        if (a != null) {
            a.runOnUiThread(() -> a.handleVoiceTranscript(trimmed));
            return;
        }
        if ((sessionPreserved || interfaceTakingMic) && ChatSessionRegistry.isActive()
                && appContext != null) {
            Intent i = new Intent(appContext, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            i.putExtra("resume_chat", true);
            i.putExtra(EXTRA_PENDING_TRANSCRIPT, trimmed);
            appContext.startActivity(i);
        }
    }

    public static void recordToolReply(String reply) {
        MainActivity a = host != null ? host.get() : null;
        if (a != null) {
            a.recordToolReplyFromBridge(reply);
            return;
        }
        if (appContext != null && ChatSessionRegistry.isActive()) {
            ChatSessionRegistry.get(appContext).recordToolReply(reply);
        }
    }

    public static void requestInterfaceListening() {
        if (!interfaceTakingMic) return;
        VoiceManager voice = sharedVoice;
        if (voice == null) return;
        PegaseInterfaceState.requestResumeListening();
    }
}
