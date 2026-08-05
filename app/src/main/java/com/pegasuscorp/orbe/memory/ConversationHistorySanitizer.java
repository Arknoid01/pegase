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
     * Plafond optionnel pour l'affichage UI (bulles, bandeaux) — jamais pour le prompt LLM.
     */
    public static final int MAX_DISPLAY_ASSISTANT_CHARS = 8_000;

    private ConversationHistorySanitizer() {}

    public static String forUser(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * Nettoie une réponse assistant pour stockage et réinjection au LLM — sans troncature.
     * (La limite de contexte est gérée par {@link ConversationHistorySelector}, pas ici.)
     */
    public static String forAssistant(String text) {
        return cleanAssistant(text);
    }

    /** Variante tronquée pour l'affichage seulement (bulles Discussion, aperçus). */
    public static String forDisplayAssistant(String text) {
        String out = cleanAssistant(text);
        if (out.length() > MAX_DISPLAY_ASSISTANT_CHARS) {
            out = out.substring(0, MAX_DISPLAY_ASSISTANT_CHARS - 1).trim() + "…";
        }
        return out;
    }

    private static String cleanAssistant(String text) {
        if (text == null) return "";
        if (ChatSpokenErrors.isHistoryPoison(text)) {
            return "";
        }
        String out = ToolDispatcher.cleanForDisplay(text);
        out = PegasePrompt.sanitizeForDisplay(out);
        if (ChatSpokenErrors.isHistoryPoison(out)) {
            return "";
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

    /**
     * Une réponse en erreur vient d'être retirée : la question qu'elle laissait sans
     * réponse part avec elle, et une trace unique les remplace.
     *
     * <p>Retirer la seule réponse laissait deux questions consécutives, que la
     * fusion plus bas réduisait à la dernière — la précédente disparaissait sans
     * laisser de trace. Échecs consécutifs : une seule note, pas une par échange.
     */
    /**
     * Une vraie réponse a-t-elle fini par suivre cette erreur ? Un retry qui aboutit
     * laisse le poison en place mais l'échange n'est pas perdu : la question doit être
     * conservée. Une nouvelle question avant toute réponse valide signe l'abandon.
     */
    private static boolean exchangeRecovered(List<ChatBackend.Turn> turns, int poisonIndex) {
        for (int i = poisonIndex + 1; i < turns.size(); i++) {
            ChatBackend.Turn t = turns.get(i);
            if (t == null || t.system) continue;
            if (t.fromUser) return false;
            if (!ChatSpokenErrors.isHistoryPoison(t.text)) return true;
        }
        return false;
    }

    private static void dropFailedExchange(List<ChatBackend.Turn> out) {
        if (!out.isEmpty() && out.get(out.size() - 1).fromUser) {
            out.remove(out.size() - 1);
        }
        boolean alreadyNoted = !out.isEmpty()
                && ChatSpokenErrors.LOST_EXCHANGE_NOTE.equals(out.get(out.size() - 1).text);
        if (!alreadyNoted) {
            out.add(new ChatBackend.Turn(false, ChatSpokenErrors.LOST_EXCHANGE_NOTE));
        }
    }

    private static List<ChatBackend.Turn> normalizeInternal(List<ChatBackend.Turn> turns,
            boolean dropTrailingUser) {
        if (turns == null || turns.isEmpty()) return new ArrayList<>();
        List<ChatBackend.Turn> out = new ArrayList<>();
        for (int idx = 0; idx < turns.size(); idx++) {
            ChatBackend.Turn turn = turns.get(idx);
            if (turn == null) continue;
            if (turn.system) continue; // hints éphémères — pas en mémoire longue
            if (!turn.fromUser && ChatSpokenErrors.isHistoryPoison(turn.text)) {
                if (!exchangeRecovered(turns, idx)) dropFailedExchange(out);
                continue;
            }
            String stored = forStorage(turn.fromUser, turn.text);
            if (stored.isEmpty()) continue;
            ChatBackend.Turn cleaned = new ChatBackend.Turn(turn.fromUser, stored);
            if (!out.isEmpty()
                    && cleaned.fromUser
                    && out.get(out.size() - 1).fromUser) {
                // Reformulation : la dernière question prime. Règle voulue et testée
                // (normalize_collapsesConsecutiveUsers) — ne pas y insérer de trace.
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

    /**
     * Retire les tours assistant « quota / erreur transitoire » déjà en mémoire.
     * Utilisé avant envoi LLM et au chargement session.
     */
    public static List<ChatBackend.Turn> stripPoisonTurns(List<ChatBackend.Turn> turns) {
        if (turns == null || turns.isEmpty()) return new ArrayList<>();
        List<ChatBackend.Turn> out = new ArrayList<>();
        for (int idx = 0; idx < turns.size(); idx++) {
            ChatBackend.Turn t = turns.get(idx);
            if (t == null) continue;
            if (!t.fromUser && ChatSpokenErrors.isHistoryPoison(t.text)) {
                // Même règle que normalize() : l'échange raté part en entier, remplacé
                // par une trace unique. Sans ça, ce chemin laissait la question sans
                // réponse et le prochain tour utilisateur l'écrasait silencieusement.
                if (!exchangeRecovered(turns, idx)) dropFailedExchange(out);
                continue;
            }
            out.add(t);
        }
        return out;
    }

    public static void dropTrailingUserTurns(List<ChatBackend.Turn> turns) {
        while (turns != null && !turns.isEmpty() && turns.get(turns.size() - 1).fromUser) {
            turns.remove(turns.size() - 1);
        }
    }
}
