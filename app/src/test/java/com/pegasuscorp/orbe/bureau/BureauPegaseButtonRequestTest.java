package com.pegasuscorp.orbe.bureau;

import org.junit.Test;

import static org.junit.Assert.*;

public class BureauPegaseButtonRequestTest {

    @Test
    public void usesLastQuestionLineIgnoringPegaseReplies() {
        String doc = "# Notes\ncomment g\u00e9rer le rebond d'une balle en html ?\n"
                + "> \uD83D\uDCA1 P\u00e9gase : deja repondu\n\n";
        assertEquals(
                "comment g\u00e9rer le rebond d'une balle en html ?",
                BureauMarkdownBrain.resolvePegaseButtonRequest(doc));
    }

    @Test
    public void defaultsToEditWhenLastLineIsNotQuestion() {
        String doc = "# Projet\n- item\nUne phrase sans interrogation.\n";
        assertEquals(
                BureauMarkdownBrain.DEFAULT_PEGASE_EDIT_REQUEST,
                BureauMarkdownBrain.resolvePegaseButtonRequest(doc));
    }

    @Test
    public void defaultsWhenOnlyQuoteLines() {
        assertEquals(
                BureauMarkdownBrain.DEFAULT_PEGASE_EDIT_REQUEST,
                BureauMarkdownBrain.resolvePegaseButtonRequest("> \uD83D\uDCA1 P\u00e9gase : hi\n\n"));
    }

    @Test
    public void keywordWithoutQuestionMark() {
        assertEquals(
                "pourquoi le brief ignore diag",
                BureauMarkdownBrain.resolvePegaseButtonRequest("notes\npourquoi le brief ignore diag\n"));
    }
}