package com.pegasuscorp.orbe.memory;

import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.chat.ChatSpokenErrors;
import com.pegasuscorp.orbe.llm.PegasePrompt;
import com.pegasuscorp.orbe.tools.ToolDispatcher;

import java.util.ArrayList;
import java.util.List;

/** Nettoie et compacte l'historique avant stockage et envoi au LLM. */
public final class ConversationHistorySanitizer {

    /** Tours conservés en mémoire session (affichage + persistance). */
    public static final int MAX_STORED_TURNS = 14;

    /**
     * Longueur max d'une réponse assistant en historique / affichage.
     * 420 coupait les histoires — budget texte large (voix reste limitée par TTS / max_tokens).
     */
    public static final int MAX_ASSISTANT_CHARS = 8_000;

    private ConversationHistorySanitizer() {}

    public static String forUser(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }

    public static String forAssistant(String text) {
        if (text == null) return "";
        if (ChatSpokenErrors.isHistoryPoison(text)) {
            return ChatSpokenErrors.HISTORY_SAFE_TRANSIENT_ERROR;
        }
        String out = ToolDispatcher.cleanForDisplay(text);
        out = PegasePrompt.sanitizeForDisplay(out);
        if (ChatSpokenErrors.isHistoryPoison(out)) {
            return ChatSpokenErrors.HISTORY_SAFE_TRANSIENT_ERROR;
        }
        if (out.length() > MAX_ASSISTANT_CHARS) {
            out = out.substring(0, MAX_ASSISTANT_CHARS - 1).trim() + "…";
        }
        return out;
    }

    public static String forStorage(boolean fromUser, String text) {
        return fromUser ? forUser(text) : forAssistant(text);
    }

    /**
     * Nettoie l'historique pour affichage / reprise de session.
     * Conserve un éventuel tour utilisateur final (demande en cours).
     */
    public static List<ChatBackend.Turn> normalizeKeepingTrailingUser(List<ChatBackend.Turn> turns) {
        return normalizeInternal(turns, false);
    }

    /**
     * Nettoie l'historique pour archivage / prompt LLM « échange terminé ».
     * Retire les tours utilisateur orphelins en fin de liste.
     */
    public static List<ChatBackend.Turn> normalize(List<ChatBackend.Turn> turns) {
        return normalizeInternal(turns, true);
    }

    private static List<ChatBackend.Turn> normalizeInternal(List<ChatBackend.Turn> turns,
            boolean dropTrailingUser) {
        if (turns == null || turns.isEmpty()) return new ArrayList<>();
        List<ChatBackend.Turn> out = new ArrayList<>();
        for (ChatBackend.Turn turn : turns) {
            if (turn == null) continue;
            if (turn.system) continue; // hints éphémères — pas en mémoire longue
            String stored = forStorage(turn.fromUser, turn.text);
            if (stored.isEmpty()) continue;
            ChatBackend.Turn cleaned = new ChatBackend.Turn(turn.fromUser, stored);
            if (!out.isEmpty()
                    && cleaned.fromUser
                    && out.get(out.size() - 1).fromUser) {
                out.set(out.size() - 1, cleaned);
            } else {
                out.add(cleaned);
            }
        }
        if (dropTrailingUser) {
            dropTrailingUserTurns(out);
        }
        while (out.size() > MAX_STORED_TURNS) {
            out.remove(0);
        }
        return out;
    }

    public static void dropTrailingUserTurns(List<ChatBackend.Turn> turns) {
        while (turns != null && !turns.isEmpty() && turns.get(turns.size() - 1).fromUser) {
            turns.remove(turns.size() - 1);
        }
    }
}
