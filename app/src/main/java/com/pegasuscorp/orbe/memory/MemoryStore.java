package com.pegasuscorp.orbe.memory;

import com.pegasuscorp.orbe.chat.ChatBackend;

import java.util.List;

/**
 * Persistance des tours de conversation — interface étroite pour {@link com.pegasuscorp.orbe.chat.ConversationManager}.
 * PegaseSession étape 0 : découpler du {@link MemoryRepository} complet (souvenirs, sessions, fichiers).
 */
public interface MemoryStore {

    List<ChatBackend.Turn> getRecentTurns();

    void setRecentTurns(List<ChatBackend.Turn> turns);

    void addTurn(boolean fromUser, String text);

    /** Remplace le dernier tour utilisateur (nouvelle demande avant réponse). */
    void replaceLastUserTurn(String text);

    void replaceLastAssistantTurn(String text);

    /** Réinitialise l'historique persistant (tests, redémarrage propre). */
    void clearRecentTurns();
}
