package com.pegasuscorp.orbe.routing;

/**
 * Phrase extraite d'une conversation, avec outil pré-sélectionné.
 */
public final class PhraseCandidate {

    public final String phrase;
    /** Hint outil : diag, notepad, timer, … ou {@code none}. */
    public String toolHint;
    /** true = à garder à la validation. */
    public boolean accepted = true;

    public PhraseCandidate(String phrase, String toolHint) {
        this.phrase = phrase != null ? phrase.trim() : "";
        this.toolHint = toolHint != null && !toolHint.isEmpty() ? toolHint : "none";
    }
}
