package com.pegasuscorp.orbe.llm;

import android.content.Context;

import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.contextstore.AttachedContextInjector;
import com.pegasuscorp.orbe.memory.ConversationHistorySelector;
import com.pegasuscorp.orbe.memory.MemoryPromptBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Construit les messages pour le moteur local à partir de l'historique Pégase.
 */
public final class ChatPromptBuilder {

    private ChatPromptBuilder() {}

    public static List<ChatMessage> fromConversation(Context context,
                                                     List<ChatBackend.Turn> history,
                                                     String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        String system = MemoryPromptBuilder.buildFullSystem(context, userMessage);
        messages.add(new ChatMessage(ChatMessage.ROLE_SYSTEM, system));
        List<ChatBackend.Turn> promptHistory = ConversationHistorySelector.selectForPrompt(
                context, history, userMessage);
        if (promptHistory != null) {
            for (ChatBackend.Turn turn : promptHistory) {
                if (turn.system) {
                    messages.add(new ChatMessage(ChatMessage.ROLE_SYSTEM, turn.text));
                } else {
                    messages.add(new ChatMessage(
                            turn.fromUser ? ChatMessage.ROLE_USER : ChatMessage.ROLE_ASSISTANT,
                            turn.text));
                }
            }
        }
        messages.add(new ChatMessage(ChatMessage.ROLE_USER,
                AttachedContextInjector.wrapUserMessage(context, userMessage)));
        return messages;
    }
}
