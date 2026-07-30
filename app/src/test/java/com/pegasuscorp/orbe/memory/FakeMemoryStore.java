package com.pegasuscorp.orbe.memory;

import com.pegasuscorp.orbe.chat.ChatBackend;

import java.util.ArrayList;
import java.util.List;

/** Mémoire en RAM pour les tests unitaires — zéro disque, zéro Android. */
public final class FakeMemoryStore implements MemoryStore {

    private final List<ChatBackend.Turn> turns = new ArrayList<>();

    @Override
    public List<ChatBackend.Turn> getRecentTurns() {
        return new ArrayList<>(turns);
    }

    @Override
    public void setRecentTurns(List<ChatBackend.Turn> recent) {
        turns.clear();
        if (recent != null) {
            turns.addAll(ConversationHistorySanitizer.normalizeKeepingTrailingUser(recent));
        }
    }

    @Override
    public void addTurn(boolean fromUser, String text) {
        String stored = ConversationHistorySanitizer.forStorage(fromUser, text);
        if (stored.isEmpty()) return;
        if (fromUser && !turns.isEmpty() && turns.get(turns.size() - 1).fromUser) {
            turns.set(turns.size() - 1, new ChatBackend.Turn(true, stored));
        } else {
            turns.add(new ChatBackend.Turn(fromUser, stored));
        }
        trim();
    }

    @Override
    public void replaceLastUserTurn(String text) {
        String stored = ConversationHistorySanitizer.forUser(text);
        if (stored.isEmpty()) return;
        if (turns.isEmpty() || !turns.get(turns.size() - 1).fromUser) {
            addTurn(true, stored);
            return;
        }
        turns.set(turns.size() - 1, new ChatBackend.Turn(true, stored));
    }

    @Override
    public void replaceLastAssistantTurn(String text) {
        String stored = ConversationHistorySanitizer.forAssistant(text);
        if (stored.isEmpty()) return;
        int lastUser = -1;
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i).fromUser) {
                lastUser = i;
                break;
            }
        }
        for (int i = turns.size() - 1; i > lastUser; i--) {
            if (!turns.get(i).fromUser) {
                turns.set(i, new ChatBackend.Turn(false, stored));
                return;
            }
        }
        addTurn(false, stored);
    }

    @Override
    public void clearRecentTurns() {
        turns.clear();
    }

    private void trim() {
        while (turns.size() > ConversationHistorySanitizer.MAX_STORED_TURNS) {
            turns.remove(0);
        }
    }
}
