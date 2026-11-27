package com.pegasuscorp.orbe.memory;

import com.pegasuscorp.orbe.chat.ChatBackend;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class ActiveContextTest {

    @Test
    public void retientLObjetEvoqueParLUtilisateur() {
        String ctx = ActiveContext.fromHistory(Arrays.asList(
                new ChatBackend.Turn(true, "J'ai acheté une voiture."),
                new ChatBackend.Turn(false, "Félicitations ! Tu as choisi quel modèle ?")));
        assertEquals("[Contexte actif] dernier objet évoqué : voiture.", ctx);
    }

    @Test
    public void videSansObjet() {
        assertEquals("", ActiveContext.fromHistory(Collections.<ChatBackend.Turn>emptyList()));
        assertEquals("", ActiveContext.fromHistory(Arrays.asList(
                new ChatBackend.Turn(true, "Met un minuteur de 45 min."))));
    }

    @Test
    public void secretsNeSontJamaisMemorises() {
        assertTrue(EphemeralMemoryFilter.isSensitiveMemory("Yannick a donné son code de carte : 1234."));
        assertTrue(EphemeralMemoryFilter.isSensitiveMemory("Mon mot de passe banque est celui de Bitwarden"));
        assertTrue(EphemeralMemoryFilter.isSensitiveMemory("code PIN 0412"));
        assertTrue(EphemeralMemoryFilter.isSensitiveMemory("Numéro de carte 4970 1234 5678 9012"));
        assertFalse(EphemeralMemoryFilter.isSensitiveMemory("Mon anniversaire le 2 août"));
        assertFalse(EphemeralMemoryFilter.isSensitiveMemory("Yannick préfère le bleu"));
    }
}
