package com.pegasuscorp.orbe.session;

import android.app.Activity;
import android.widget.Toast;

import com.pegasuscorp.orbe.chat.ChatSessionRegistry;
import com.pegasuscorp.orbe.chat.ConversationManager;
import com.pegasuscorp.orbe.iface.PegaseInterfaceHost;

import java.util.function.IntConsumer;

/**
 * Pose confirm / choix dans la discussion (bulle) au lieu d'un AlertDialog système.
 */
public final class ChatConfirmBridge {

    private ChatConfirmBridge() {}

    public static void askConfirm(Activity activity, String question,
            Runnable onConfirm, Runnable onCancel) {
        PendingToolConfirm.set(question, onConfirm, onCancel);
        postAndOpenDiscussion(activity, question);
    }

    public static void askChoice(Activity activity, String title, String[] labels,
            IntConsumer onChosen, Runnable onCancel) {
        String prompt = PendingToolConfirm.formatChoicePrompt(title, labels);
        PendingToolConfirm.setChoice(prompt, labels, onChosen, onCancel);
        postAndOpenDiscussion(activity, prompt);
    }

    private static void postAndOpenDiscussion(Activity activity, String prompt) {
        if (activity == null) return;
        try {
            ConversationManager conv = ChatSessionRegistry.get(activity);
            if (!conv.isActive()) conv.enter();
            conv.recordToolReply(prompt);
        } catch (Exception ignored) {
        }
        activity.runOnUiThread(() -> {
            if (activity instanceof PegaseInterfaceHost) {
                ((PegaseInterfaceHost) activity).openDiscussionTab();
            } else {
                Toast.makeText(activity,
                        "Réponds dans Discussion (oui / numéro).",
                        Toast.LENGTH_LONG).show();
            }
        });
    }
}
