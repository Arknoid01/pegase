package com.pegasuscorp.orbe.memory;

import org.junit.Test;

import static org.junit.Assert.*;

public class EphemeralMemoryFilterTest {

    @Test
    public void rejectsUiClickAcks() {
        assertTrue(EphemeralMemoryFilter.isNoise("Clic envoyé sur « Cursor »."));
        assertTrue(EphemeralMemoryFilter.isNoise("Clic envoyé sur Send"));
        assertTrue(EphemeralMemoryFilter.isNoise("J'ai cliqué sur OK"));
    }

    @Test
    public void rejectsOtherUiAcks() {
        assertTrue(EphemeralMemoryFilter.isNoise("Texte saisi."));
        assertTrue(EphemeralMemoryFilter.isNoise("Défilement effectué."));
        assertTrue(EphemeralMemoryFilter.isNoise("Retour arrière."));
    }

    @Test
    public void rejectsLlmUiNegotiationReformulations() {
        assertTrue(EphemeralMemoryFilter.isNoise(
                "Pégase nécessite l'identifiant exact de la vue pour cliquer."));
        assertTrue(EphemeralMemoryFilter.isNoise(
                "Elle a déjà demandé cet identifiant."));
        assertTrue(EphemeralMemoryFilter.isNoise(
                "Yannick veut cliquer sur pokemon sleep"));
    }

    @Test
    public void keepsRealFacts() {
        assertFalse(EphemeralMemoryFilter.isNoise("J'aime le café noir le matin"));
        assertFalse(EphemeralMemoryFilter.isNoise("Mon projet s'appelle Orion"));
        assertFalse(EphemeralMemoryFilter.isNoise("Yann travaille souvent dans Cursor"));
    }

    @Test
    public void pendingUsesVerbStemsNotFixedPhrases() {
        assertTrue(EphemeralMemoryFilter.isDurablePending(
                "Rappelle-moi demain d'appeler le dentiste"));
        assertTrue(EphemeralMemoryFilter.isDurablePending(
                "Il faudra finir Orion plus tard"));
        assertTrue(EphemeralMemoryFilter.isDurablePending(
                "Faudra que j'y repense"));
        assertTrue(EphemeralMemoryFilter.isDurablePending(
                "On verra ça après le repas"));
        assertTrue(EphemeralMemoryFilter.isDurablePending(
                "Fait moi penser à prendre mes écouteurs"));
        assertTrue(EphemeralMemoryFilter.isDurablePending(
                "Pensez à prendre vos clés"));
        assertTrue(EphemeralMemoryFilter.isDurablePending(
                "Il faudra que je prenne mes écouteurs"));
        assertTrue(EphemeralMemoryFilter.isDurablePending(
                "N'oublie pas de prendre le chargeur"));
        assertTrue(EphemeralMemoryFilter.isDurablePending(
                "Pense à prendre tes écouteurs demain"));
        assertFalse(EphemeralMemoryFilter.isDurablePending(
                "Yannick veut cliquer sur pokemon sleep"));
        assertFalse(EphemeralMemoryFilter.isDurablePending(
                "En attente de l'identifiant de vue"));
        assertFalse(EphemeralMemoryFilter.isDurablePending(
                "Il me faut encore ce repère visuel"));
        assertFalse(EphemeralMemoryFilter.isDurablePending(
                "Qu'elle est la météo pour demain ?"));
        assertTrue(EphemeralMemoryFilter.hasTimeAnchorOnly(
                "Qu'elle est la météo pour demain ?"));
        assertFalse(EphemeralMemoryFilter.isDurablePending("ok"));
    }

    @Test
    public void uiToolIdsDetected() {
        assertTrue(EphemeralMemoryFilter.isUiToolId("ui_action"));
        assertTrue(EphemeralMemoryFilter.isUiToolId("ui_explain"));
        assertTrue(EphemeralMemoryFilter.isUiToolId("UI_SEARCH"));
        assertFalse(EphemeralMemoryFilter.isUiToolId("weather"));
        assertFalse(EphemeralMemoryFilter.isUiToolId(null));
    }

    @Test
    public void dedupesNearDuplicatePendingFormulations() {
        assertTrue(EphemeralMemoryFilter.samePendingIntent(
                "rappel de vider le lavevaisselle",
                "Fait moi penser à vider le lave vaisselle"));
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        list.add("rappel de vider le lavevaisselle");
        list.add("Fait moi penser à vider le lave vaisselle");
        list.add("Rappelle-moi demain d'appeler le dentiste");
        EphemeralMemoryFilter.dedupePendingList(list);
        assertEquals(2, list.size());
        // Garde la formulation la plus longue pour le cluster lave-vaisselle
        assertTrue(list.stream().anyMatch(s -> s.contains("Fait moi penser")));
        assertTrue(list.stream().anyMatch(s -> s.contains("dentiste")));
    }
}
