package com.pegasuscorp.orbe.llm;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Les noms techniques (modèles, paquets, versions) doivent traverser le nettoyage oral
 * sans perdre leurs tirets.
 *
 * <p>Régression corrigée : la classe de caractères des emojis déclarait des plages de
 * demi-codets, ce qui la faisait mordre bien au-delà des symboles — « gpt-oss-120b »
 * était archivé « gptoss120b », corrompant l'historique et les traces de diagnostic.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PegasePromptTechnicalNamesTest {

    private static final String PLAIN = "modele groq/openai/gpt-oss-120b puis bilan.";
    private static final String RICH =
            "6h30 · groq/openai/gpt-oss-120b → next : bilan diag local.";

    @Test
    public void display_keepsHyphenatedModelName() {
        assertEquals(PLAIN, PegasePrompt.sanitizeForDisplay(PLAIN));
        assertEquals(RICH, PegasePrompt.sanitizeForDisplay(RICH));
    }

    @Test
    public void speech_keepsHyphenatedModelName() {
        assertTrue(PegasePrompt.sanitizeForSpeech(PLAIN).contains("gpt-oss-120b"));
    }

    @Test
    public void display_keepsOtherHyphenatedTechnicalNames() {
        // Noms simples uniquement : la casse chameau et les identifiants pointés sont
        // retouchés par des règles de prononciation distinctes, hors sujet ici.
        for (String s : new String[]{"sherpa-onnx", "v2-hard-neg"}) {
            assertEquals(s, PegasePrompt.sanitizeForDisplay(s));
        }
    }

    /**
     * Cas connu, distinct des tirets : une règle de prononciation sépare les mots en
     * casse chameau, « all-MiniLM-L6-v2 » devenant « all-Mini LM-L6-v2 ». Utile à l'oral,
     * discutable à l'archivage — laissé tel quel faute d'arbitrage. Les tirets, eux,
     * doivent survivre.
     */
    @Test
    public void display_camelCaseSplit_stillKeepsHyphens() {
        String out = PegasePrompt.sanitizeForDisplay("all-MiniLM-L6-v2");
        assertTrue(out, out.startsWith("all-"));
        assertTrue(out, out.endsWith("-L6-v2"));
    }

    /** Le nettoyage doit continuer à retirer les emojis, y compris hors plan de base. */
    @Test
    public void display_stillRemovesEmoji() {
        assertEquals("bilan ok", PegasePrompt.sanitizeForDisplay("bilan ok 😀"));
        assertEquals("cyan", PegasePrompt.sanitizeForDisplay("cyan ✨"));
    }
}
