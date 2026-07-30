package com.pegasuscorp.orbe.orion.prompt;

/** Niveau de clarté avant compilation vers Orion. */
public enum PromptReadiness {
    /** Assez précis → Mission immédiate. */
    READY,
    /** Interprétation proposée → validation rapide (oui / corrige / fais au mieux). */
    CLARIFICATION_RECOMMENDED,
    /** Impossible de compiler sans réponse (max 2 questions). */
    CLARIFICATION_REQUIRED
}
