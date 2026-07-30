package com.pegasuscorp.orbe.bureau;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BureauPlanningBrainTest {

    @Test
    public void parseInterview_needInfo() {
        String raw = "Quelques précisions utiles.\n\n"
                + "READINESS: NEED_INFO\n"
                + "QUESTIONS:\n"
                + "1. Android uniquement ?\n"
                + "2. Données locales ou cloud ?\n";
        BureauPlanningBrain.InterviewTurnResult r = BureauPlanningBrain.parseInterviewReply(raw);
        assertEquals(BureauPlanningBrain.Readiness.NEED_INFO, r.readiness);
        assertFalse(r.isReady());
        assertEquals(2, r.questions.size());
        assertTrue(r.speakText.contains("précisions") || r.speakText.contains("Android"));
        assertFalse(r.speakText.contains("READINESS"));
        assertFalse(BureauPlanningBrain.isGenericClarifyFallback(r.speakText));
    }

    @Test
    public void parseInterview_ready() {
        String raw = "Parfait, on peut créer le plan.\n"
                + "READINESS: READY\n"
                + "SUMMARY: App sport avec timers et Room.\n";
        BureauPlanningBrain.InterviewTurnResult r = BureauPlanningBrain.parseInterviewReply(raw);
        assertTrue(r.isReady());
        assertTrue(r.summary.contains("timers"));
    }

    @Test
    public void parseMaterialize_assignsIds() {
        String json = "{"
                + "\"title\":\"Sport\","
                + "\"vision\":\"Suivi des seances.\","
                + "\"objectives\":[\"Programmes\"],"
                + "\"decisions\":[{\"text\":\"Compose\",\"confidence\":\"CONFIRMED\"}],"
                + "\"tasks\":[{\"text\":\"Maquette\",\"done\":false}],"
                + "\"openQuestions\":[{\"text\":\"Cloud?\"}]"
                + "}";
        assertNotNull(BureauPlanningBrain.extractJsonObject(json));
        BureauProject p = BureauPlanningBrain.parseMaterializeJson(json, "Fallback");
        assertNotNull(p);
        assertEquals("Sport", p.title);
        assertEquals("sport", p.slug);
        assertFalse(p.id.isEmpty());
        assertEquals(1, p.decisions.size());
        assertFalse(p.decisions.get(0).id.isEmpty());
        assertEquals(BureauProject.Confidence.CONFIRMED, p.decisions.get(0).confidence);
        assertEquals(1, p.tasks.size());
        assertFalse(p.tasks.get(0).id.isEmpty());
        assertEquals(1, p.openQuestions.size());
        assertTrue(BureauPlanningBrain.hasSubstance(p));
    }

    @Test
    public void parseMaterialize_nullWhenNoJson() {
        assertNull(BureauPlanningBrain.parseMaterializeJson("pas de json ici", "Titre"));
    }

    @Test
    public void parseMaterialize_nullWhenTitleOnly() {
        assertNull(BureauPlanningBrain.parseMaterializeJson(
                "{\"title\":\"Vide\"}", "Fallback"));
    }

    @Test
    public void extractJsonObject_fromFence() {
        String raw = "```json\n{\"title\":\"X\"}\n```";
        String obj = BureauPlanningBrain.extractJsonObject(raw);
        assertNotNull(obj);
        assertTrue(obj.contains("\"title\""));
    }

    @Test
    public void parseCommandsReply() {
        String raw = "J'ajoute la tâche.\nCOMMANDS:\n[{\"op\":\"appendTask\",\"text\":\"X\"}]";
        BureauPlanningBrain.CommandsReply r = BureauPlanningBrain.parseCommandsReply(raw);
        assertTrue(r.speak.contains("ajoute"));
        assertTrue(r.commandsJson.contains("appendTask"));
    }

    @Test
    public void buildInterviewPrompt_includesHistory() {
        List<BureauChatStore.Turn> turns = new ArrayList<>();
        turns.add(new BureauChatStore.Turn(true, "App sport", 1));
        String p = BureauPlanningBrain.buildInterviewPrompt("Sport", turns, "Android");
        assertTrue(p.contains("MODE PLANIFICATION"));
        assertTrue(p.contains("App sport"));
        assertTrue(p.contains("READINESS"));
    }

    @Test
    public void parseInterview_readyLooseMarker() {
        String raw = "Ok on y va.\n\nREADINESS = READY\nSUMMARY: App sport";
        BureauPlanningBrain.InterviewTurnResult r = BureauPlanningBrain.parseInterviewReply(raw);
        assertTrue(r.isReady());
    }

    @Test
    public void parseInterview_readyFrenchFallback() {
        String raw = "J'ai assez d'éléments pour créer le plan.";
        BureauPlanningBrain.InterviewTurnResult r = BureauPlanningBrain.parseInterviewReply(raw);
        assertTrue(r.isReady());
    }

    @Test
    public void parseInterview_readyInMiddleOfReply() {
        String raw = "Super, on avance bien.\n"
                + "READINESS: READY\n"
                + "SUMMARY: App sport timers.\n"
                + "Si tu veux on peut encore parler du design, sinon crée le plan.";
        BureauPlanningBrain.InterviewTurnResult r = BureauPlanningBrain.parseInterviewReply(raw);
        assertTrue(r.isReady());
        assertTrue(r.summary.contains("timers"));
        assertFalse(r.speakText.contains("READINESS"));
        assertTrue(r.speakText.contains("design") || r.speakText.contains("avance"));
    }

    @Test
    public void parseInterview_lastMarkerWins() {
        String raw = "READINESS: NEED_INFO\n"
                + "QUESTIONS:\n1. Plateforme ?\n"
                + "En fait j'ai assez.\n"
                + "READINESS: READY\n"
                + "SUMMARY: OK";
        BureauPlanningBrain.InterviewTurnResult r = BureauPlanningBrain.parseInterviewReply(raw);
        assertTrue(r.isReady());
    }

    @Test
    public void parseInterview_needInfo_showsQuestionsNotGenericLoop() {
        String raw = "READINESS: NEED_INFO\n"
                + "QUESTIONS:\n"
                + "1. Android uniquement ?\n"
                + "2. Données locales ou cloud ?\n";
        BureauPlanningBrain.InterviewTurnResult r = BureauPlanningBrain.parseInterviewReply(raw);
        assertFalse(r.isReady());
        assertEquals(2, r.questions.size());
        assertFalse(BureauPlanningBrain.isGenericClarifyFallback(r.speakText));
        assertTrue(r.speakText.contains("Android"));
        assertTrue(r.speakText.contains("cloud") || r.speakText.contains("Données"));
    }

    @Test
    public void buildDisplaySpeak_replacesGenericFallback() {
        String out = BureauPlanningBrain.buildDisplaySpeak(
                "J'ai quelques questions pour clarifier.",
                BureauPlanningBrain.Readiness.NEED_INFO,
                "",
                java.util.Arrays.asList("Quel moteur TTS ?", "Room ou Realm ?"));
        assertTrue(out.contains("TTS"));
        assertFalse(BureauPlanningBrain.isGenericClarifyFallback(out));
    }
}
