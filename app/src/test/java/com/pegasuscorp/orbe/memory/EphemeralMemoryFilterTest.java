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

    /** Phrases exactes qui ont pollué la mémoire du téléphone (2026-11-25). */
    @Test
    public void rejectsAgentActionsThatPolluted() {
        assertTrue(EphemeralMemoryFilter.isAgentActionMemory("Pégase a ouvert l'application Brave"));
        assertTrue(EphemeralMemoryFilter.isAgentActionMemory("Pégase a exécuté l'action d'ouverture de l'application Cursor"));
        assertTrue(EphemeralMemoryFilter.isAgentActionMemory("Lancement de l'application Cursor"));
        assertTrue(EphemeralMemoryFilter.isAgentActionMemory("Ne rien faire de plus pour l'application Cursor"));
        assertTrue(EphemeralMemoryFilter.isAgentActionMemory("Pégase a indiqué qu'il faut déverrouiller le téléphone"));
        assertTrue(EphemeralMemoryFilter.isAgentActionMemory("Yannick veut ouvrir l’application SMS"));
        assertTrue(EphemeralMemoryFilter.isAgentActionMemory("Clic effectué sur le bouton de message vocal"));
        assertTrue(EphemeralMemoryFilter.isAgentActionMemory("Pégase a demandé le déverrouillage du téléphone pour pouvoir créer le minuteur"));
        assertTrue(EphemeralMemoryFilter.isAgentActionMemory("{\"fact\":\"Yannick a travaillé sur des harnais\"}"));
        assertTrue(EphemeralMemoryFilter.isAgentActionMemory("test desktop auto-export"));
        assertTrue(EphemeralMemoryFilter.isAgentActionMemory("VALIDATIONPC20260810 souvenir test validation PC"));
        assertTrue(EphemeralMemoryFilter.isAgentActionMemory("Smoke_v3_memoire_pegase"));
        // Rejetés aussi par le chemin de session.
        assertFalse(EphemeralMemoryFilter.isDurableSessionItem("Pégase a ouvert l'application Brave"));
    }

    /** Les faits durables sur Yannick doivent passer. */
    @Test
    public void keepsDurableFactsAboutUser() {
        assertFalse(EphemeralMemoryFilter.isAgentActionMemory("Mon chien s'apelle : Rex"));
        assertFalse(EphemeralMemoryFilter.isAgentActionMemory("Yannick préfère le bleu"));
        assertFalse(EphemeralMemoryFilter.isAgentActionMemory("Yannick préfère un rythme de conversation plus lent à cause des délais"));
        assertFalse(EphemeralMemoryFilter.isAgentActionMemory("Yannick travaille sur Fableris, une application de livre interactif"));
        assertFalse(EphemeralMemoryFilter.isAgentActionMemory("Mon anniversaire le 2 août"));
        assertFalse(EphemeralMemoryFilter.isAgentActionMemory("Ma chérie s'apelle Léa et a un cheval appelé Tornade"));
        assertTrue(EphemeralMemoryFilter.isDurableSessionItem("Yannick préfère le bleu"));
    }

    /** Messages de test du 2026-11-26 enregistrés tels quels en souvenirs. */
    @Test
    public void rejectsQuestionsAndRequestsAsMemories() {
        String[] bad = {
                "Je crois que nous avons finalement abandonné Fableris et décidé de repartir sur une application entièrement différente.",
                "Tu sais que mon téléphone actuel est un iPhone 15 Pro. Peux-tu me donner les étapes pour activer Face ID et retrouver m…",
                "Tu te souviens que nous avons décidé ensemble de remplacer Gemma 4 E2B par un modèle de 14B paramètres, et que nous avo…",
                "Résume notre idée en quelques points et identifie les trois mécaniques de gameplay les plus importantes.",
                "Je pensais à changer ça finalement, parce que l'autre ne me convient plus vraiment. Tu en penses quoi ?",
                "Aucun rappel explicite n'a été mentionné.",
                "Ajoute un rappel dans mon agenda pour demain à 10 h : vérifier les mises à jour de Fableris.",
                "Je pensais sortir me promener ce week-end, mais la météo ne semble pas terrible.",
                "Finalement, je viens d'apprendre que j'ai seulement deux heures. Réorganise mon planning sans oublier les trois tâches.",
        };
        for (String b : bad) {
            assertFalse(b, EphemeralMemoryFilter.isDurableSessionItem(b));
            assertFalse(b, EphemeralMemoryFilter.isDurablePending(b));
        }
        // Les vrais faits et rappels passent toujours.
        assertTrue(EphemeralMemoryFilter.isDurableSessionItem("Yannick préfère le bleu"));
        assertTrue(EphemeralMemoryFilter.isDurableSessionItem("Je cherche un appartement à Annecy"));
        assertTrue(EphemeralMemoryFilter.isDurablePending("Rappelle-moi demain d'appeler le dentiste"));
        assertTrue(EphemeralMemoryFilter.isDurablePending("Tu peux me rappeler de payer ma facture d'eau ?"));
    }
}
