package com.pegasuscorp.orbe.orion;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Historique conversation Orion (process singleton — survit aux changements d'onglet).
 * Archivage disque optionnel via {@link OrionSessionArchive} si {@link #attachContext} a été appelé.
 */
public final class OrionChatHistory {

    public static final class Turn {
        public final boolean fromUser;
        public String text;

        public Turn(boolean fromUser, String text) {
            this.fromUser = fromUser;
            this.text = text != null ? text : "";
        }
    }

    private static final int MAX_TURNS = 80;
    private static final OrionChatHistory INSTANCE = new OrionChatHistory();

    private static volatile Context appContext;

    private final List<Turn> turns = new ArrayList<>();

    private OrionChatHistory() {}

    public static OrionChatHistory get() {
        return INSTANCE;
    }

    /**
     * Context applicatif pour l'archivage disque. Sans ça, l'historique reste mémoire seule.
     */
    public static void attachContext(Context ctx) {
        if (ctx != null) appContext = ctx.getApplicationContext();
    }

    public synchronized List<Turn> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(turns));
    }

    public synchronized void addUser(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String body = text.trim();
        turns.add(new Turn(true, body));
        trim();
        archiveTurn(true, body);
    }

    /** Ajoute une bulle assistant vide (streaming). */
    public synchronized Turn beginAssistant() {
        Turn t = new Turn(false, "");
        turns.add(t);
        trim();
        return t;
    }

    public synchronized void appendAssistant(String token) {
        if (token == null || token.isEmpty() || turns.isEmpty()) return;
        Turn last = turns.get(turns.size() - 1);
        if (last.fromUser) return;
        last.text = last.text + token;
    }

    public synchronized void finishAssistant(String full) {
        String archived = null;
        if (turns.isEmpty()) {
            if (full != null && !full.isEmpty()) {
                turns.add(new Turn(false, full));
                archived = full;
            }
        } else {
            Turn last = turns.get(turns.size() - 1);
            if (last.fromUser) {
                if (full != null && !full.isEmpty()) {
                    turns.add(new Turn(false, full));
                    archived = full;
                }
            } else if (full != null && !full.isEmpty()) {
                last.text = full;
                archived = full;
            } else if (last.text != null && !last.text.isEmpty()) {
                archived = last.text;
            }
        }
        trim();
        if (archived != null) archiveTurn(false, archived);
    }

    public synchronized String lastAssistantText() {
        for (int i = turns.size() - 1; i >= 0; i--) {
            Turn t = turns.get(i);
            if (!t.fromUser && t.text != null && !t.text.isEmpty()) return t.text;
        }
        return "";
    }

    public synchronized void clear() {
        turns.clear();
    }

    /**
     * Derniers tours pour le prompt (hors demande courante si déjà ajoutée).
     * Assistant tronqué pour rester dans le budget ctx.
     */
    public synchronized String formatRecentForPrompt(int maxTurns, int maxChars) {
        if (turns.isEmpty() || maxTurns <= 0 || maxChars <= 0) return "";
        int end = turns.size();
        // Exclure la demande en cours (déjà dans « === Demande === ») :
        // - dernier tour = user, ou
        // - dernier = assistant vide + user juste avant (addUser + beginAssistant)
        if (end > 0 && !turns.get(end - 1).fromUser
                && (turns.get(end - 1).text == null || turns.get(end - 1).text.isEmpty())) {
            end--;
        }
        if (end > 0 && turns.get(end - 1).fromUser) end--;
        if (end <= 0) return "";

        int start = Math.max(0, end - maxTurns);
        StringBuilder sb = new StringBuilder();
        int perAssistant = Math.max(400, maxChars / Math.max(1, maxTurns));
        for (int i = start; i < end; i++) {
            Turn t = turns.get(i);
            if (t == null || t.text == null || t.text.trim().isEmpty()) continue;
            String role = t.fromUser ? "User" : "Orion";
            String body = t.text.trim();
            if (!t.fromUser && body.length() > perAssistant) {
                body = body.substring(0, perAssistant - 1) + "…";
            }
            if (sb.length() + body.length() > maxChars) {
                int room = maxChars - sb.length() - 20;
                if (room < 40) break;
                body = body.substring(0, Math.min(body.length(), room)) + "…";
            }
            sb.append(role).append(": ").append(body).append("\n\n");
            if (sb.length() >= maxChars) break;
        }
        return sb.toString().trim();
    }

    private void archiveTurn(boolean fromUser, String text) {
        Context c = appContext;
        if (c == null) return;
        OrionSessionArchive.appendTurn(c, fromUser, text);
    }

    private void trim() {
        while (turns.size() > MAX_TURNS) {
            turns.remove(0);
        }
    }

    /** Tests. */
    public static void resetForTests() {
        INSTANCE.clear();
        appContext = null;
    }
}
