package com.pegasuscorp.orbe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.pegasuscorp.orbe.chat.ChatVoiceBridge;

import java.lang.ref.WeakReference;

/**
 * Suit si {@link PegaseInterfaceActivity} est réellement affichée (pas un compteur qui peut rester bloqué).
 */
public final class PegaseInterfaceState {

    public interface OnCloseListener {
        void onInterfaceClosed();
    }

    private static WeakReference<PegaseInterfaceActivity> active;
    private static OnCloseListener closeListener;

    public static final String EXTRA_TAB = "pegase_tab";
    public static final String EXTRA_ORION_PROMPT = "orion_prefill_prompt";
    public static final String TAB_CONVERSATION = "conversation";
    public static final String TAB_NOTEPAD = "notepad";
    public static final String TAB_ORION = "orion";
    public static final String TAB_TOOLS = "tools";
    public static final String TAB_FILES = "files";

    /** Prompt Orion en attente (Bureau → Orion) si le fragment n'est pas encore monté. */
    private static String pendingOrionPrompt;
    /** Phrase chat en attente (Intentions → discussion). */
    private static String pendingChatPhrase;

    private PegaseInterfaceState() {}

    public static void setPendingOrionPrompt(String prompt) {
        pendingOrionPrompt = prompt == null || prompt.trim().isEmpty() ? null : prompt.trim();
    }

    public static String takePendingOrionPrompt() {
        String p = pendingOrionPrompt;
        pendingOrionPrompt = null;
        return p;
    }

    public static String peekPendingOrionPrompt() {
        return pendingOrionPrompt;
    }

    public static void setPendingChatPhrase(String phrase) {
        pendingChatPhrase = phrase == null || phrase.trim().isEmpty() ? null : phrase.trim();
    }

    public static String takePendingChatPhrase() {
        String p = pendingChatPhrase;
        pendingChatPhrase = null;
        return p;
    }

    public static String peekPendingChatPhrase() {
        return pendingChatPhrase;
    }

    public static void attach(PegaseInterfaceActivity activity) {
        active = new WeakReference<>(activity);
    }

    public static void detach(PegaseInterfaceActivity activity) {
        if (active != null && active.get() == activity) {
            active = null;
            ChatVoiceBridge.onInterfaceClosed();
            if (closeListener != null) closeListener.onInterfaceClosed();
        }
    }

    public static void setOnCloseListener(OnCloseListener listener) {
        closeListener = listener;
    }

    public static boolean isOpen() {
        PegaseInterfaceActivity a = active != null ? active.get() : null;
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    public static void requestResumeListening() {
        PegaseInterfaceActivity a = active != null ? active.get() : null;
        if (a != null) a.resumeChatListening();
    }

    public static void requestReloadPiper() {
        ChatVoiceBridge.reloadSharedPiper();
    }

    public static void showNasaImage(String reply) {
        PegaseInterfaceActivity a = active != null ? active.get() : null;
        if (a != null) {
            a.showNasaImage(reply);
        }
    }

    public static void openOrBringToFront(Context ctx) {
        openOrBringToFront(ctx, null);
    }

    public static void openOrBringToFront(Context ctx, String tab) {
        ChatVoiceBridge.onInterfaceOpening();
        Intent i = new Intent(ctx, PegaseInterfaceActivity.class);
        if (!(ctx instanceof Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        if (tab != null && !tab.isEmpty()) {
            i.putExtra(EXTRA_TAB, tab);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        ctx.startActivity(i);
        if (ctx instanceof Activity) {
            ((Activity) ctx).overridePendingTransition(
                    R.anim.pegase_enter, R.anim.pegase_exit);
        }
    }

    public static void openNotepad(Context ctx) {
        openOrBringToFront(ctx, TAB_NOTEPAD);
    }

    public static void openOrion(Context ctx) {
        openOrBringToFront(ctx, TAB_ORION);
    }

    /** Ouvre Orion avec un prompt prérempli (pont Bureau). */
    public static void openOrionWithPrompt(Context ctx, String prompt) {
        setPendingOrionPrompt(prompt);
        ChatVoiceBridge.onInterfaceOpening();
        Intent i = new Intent(ctx, PegaseInterfaceActivity.class);
        if (!(ctx instanceof Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        i.putExtra(EXTRA_TAB, TAB_ORION);
        if (prompt != null && !prompt.isEmpty()) {
            i.putExtra(EXTRA_ORION_PROMPT, prompt);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        ctx.startActivity(i);
        if (ctx instanceof Activity) {
            ((Activity) ctx).overridePendingTransition(
                    R.anim.pegase_enter, R.anim.pegase_exit);
        }
    }
}
