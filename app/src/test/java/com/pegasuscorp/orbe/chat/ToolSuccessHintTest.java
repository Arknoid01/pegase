package com.pegasuscorp.orbe.chat;

import org.junit.Test;

import static org.junit.Assert.*;

public class ToolSuccessHintTest {

    @Test
    public void searchHint_forbidsBareOk() {
        String hint = ToolSuccessHint.build("search",
                "D'après ce que j'ai trouvé, CFOP est une méthode speedcubing.");
        assertTrue(ToolSuccessHint.isInformational("search"));
        assertTrue(hint.contains("INTERDIT"));
        assertTrue(hint.contains("CFOP"));
        assertFalse(hint.contains("Confirme ce succès simplement"));
    }

    @Test
    public void notepadHint_asksShortConfirm() {
        String hint = ToolSuccessHint.build("notepad", "Ajouté : lait");
        assertFalse(ToolSuccessHint.isInformational("notepad"));
        assertTrue(hint.contains("Confirme ce succès simplement"));
    }
}
