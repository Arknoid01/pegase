package com.pegasuscorp.orbe.memory;

import com.pegasuscorp.orbe.tools.ToolTag;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import java.util.EnumSet;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class ContextAnalyzerTest {

    @Test
    public void analyze_musicRequestUsesMinimalProfile() {
        Context ctx = RuntimeEnvironment.getApplication();
        ContextIntent intent = ContextAnalyzer.analyze(ctx, "mets de l'électro sur Spotify");
        assertEquals("music", intent.intent);
        assertTrue(intent.profileSections.contains(ProfileSection.ESSENTIAL));
        assertFalse(intent.profileSections.contains(ProfileSection.PROJECTS));
        assertFalse(intent.profileSections.contains(ProfileSection.NOTES));
        assertTrue(intent.allowedTools.contains(ToolTag.SPOTIFY));
        assertFalse(intent.allowedTools.contains(ToolTag.WEATHER));
    }

    @Test
    public void analyze_projectMentionIncludesProjectsSection() {
        Context ctx = RuntimeEnvironment.getApplication();
        ContextIntent intent = ContextAnalyzer.analyze(ctx, "où en est Pégase ?");
        assertEquals("project", intent.intent);
        assertTrue(intent.profileSections.contains(ProfileSection.PROJECTS));
        assertTrue(intent.entityIds.contains("project_pegase"));
    }

    @Test
    public void toolsFor_weatherMessageIncludesWeatherOnly() {
        String fold = SpeechInputNormalizer.fold("Quel temps à Lyon ?").replace('\'', ' ');
        EnumSet<ToolTag> tools = ContextAnalyzer.toolsFor("fresh_data", fold);
        assertTrue(tools.contains(ToolTag.WEATHER));
        assertTrue(tools.contains(ToolTag.NOTEPAD));
        assertTrue(tools.contains(ToolTag.MEMORY));
        assertTrue(tools.contains(ToolTag.DEVICE));
        assertFalse(tools.contains(ToolTag.SPOTIFY));
        assertFalse(tools.contains(ToolTag.CALL));
    }

    @Test
    public void toolsFor_callMessageIncludesCallNotAll() {
        String fold = SpeechInputNormalizer.fold("Appelle maman").replace('\'', ' ');
        EnumSet<ToolTag> tools = ContextAnalyzer.toolsFor("general", fold);
        assertTrue(tools.contains(ToolTag.CALL));
        assertTrue(tools.contains(ToolTag.NOTEPAD));
        assertFalse(tools.contains(ToolTag.WEATHER));
        assertFalse(tools.contains(ToolTag.SPOTIFY));
    }

    @Test
    public void toolsFor_ambiguousGeneralIncludesAll() {
        String fold = SpeechInputNormalizer.fold("Dis-moi des trucs").replace('\'', ' ');
        EnumSet<ToolTag> tools = ContextAnalyzer.toolsFor("general", fold);
        assertEquals(EnumSet.allOf(ToolTag.class), tools);
    }

    @Test
    public void toolsFor_notepadOnlyBasePlusNotepadAlreadyThere() {
        String fold = SpeechInputNormalizer.fold("Note ça dans le bloc-notes").replace('\'', ' ');
        EnumSet<ToolTag> tools = ContextAnalyzer.toolsFor("productivity", fold);
        assertTrue(tools.contains(ToolTag.NOTEPAD));
        assertTrue(tools.contains(ToolTag.NAMED_CONTEXT));
        assertEquals(4, tools.size());
    }

    @Test
    public void looksLikeCalc_requiresDigitRejectsQuestionOnly() {
        assertFalse(IntentDetector.looksLikeCalc("?"));
        assertFalse(IntentDetector.looksLikeCalc("???"));
        assertFalse(IntentDetector.looksLikeCalc("calcule quelque chose"));
        assertFalse(IntentDetector.looksLikeCalc("combien font"));
        assertFalse(IntentDetector.looksLikeCalc("ou en est le projet ?"));
        assertTrue(IntentDetector.looksLikeCalc("calcule 12 fois 4"));
        assertTrue(IntentDetector.looksLikeCalc("combien font 50 plus 36"));
        assertTrue(IntentDetector.looksLikeCalc("marge 36% sur 50 euros"));
    }

    @Test
    public void analyze_commentTuVasIncludesDiag() {
        Context ctx = RuntimeEnvironment.getApplication();
        ContextIntent intent = ContextAnalyzer.analyze(ctx, "Comment tu vas ?");
        assertEquals("diag", intent.intent);
        assertTrue(intent.allowedTools.contains(ToolTag.DIAG));
        assertFalse(intent.allowedTools.contains(ToolTag.SPOTIFY));
    }

    @Test
    public void looksLikeDiag_problemesDeDiagnostic() {
        String fold = SpeechInputNormalizer.fold("Des problèmes de diagnostic ?")
                .replace('\'', ' ');
        assertTrue(IntentDetector.looksLikeDiag(fold));
        assertTrue(IntentDetector.looksLikeDiag(
                SpeechInputNormalizer.fold("Un problème de diagnostic")
                        .replace('\'', ' ')));
    }

    @Test
    public void looksLikeDiag_problemeAvec_et_euUnProbleme() {
        assertTrue(IntentDetector.looksLikeDiag(
                SpeechInputNormalizer.fold("J'ai un problème avec le timer")
                        .replace('\'', ' ')));
        assertTrue(IntentDetector.looksLikeDiag(
                SpeechInputNormalizer.fold("Tu as eu un problème hier ?")
                        .replace('\'', ' ')));
        assertTrue(IntentDetector.looksLikeDiag(
                SpeechInputNormalizer.fold("Tu as eut un problème ?")
                        .replace('\'', ' ')));
        assertTrue(IntentDetector.looksLikeDiag(
                SpeechInputNormalizer.fold("Tu peux m'en dire plus")
                        .replace('\'', ' ')));
        assertTrue(IntentDetector.looksLikeDiag(
                SpeechInputNormalizer.fold("Tu as quelque chose ?")
                        .replace('\'', ' ')));
        assertTrue(IntentDetector.looksLikeDiag(
                SpeechInputNormalizer.fold("Comment va tu ?")
                        .replace('\'', ' ')));
    }

    @Test
    public void looksLikeDiagSearch_commonPhrases() {
        String fold = SpeechInputNormalizer.fold("Tu as déjà eu ce problème ?")
                .replace('\'', ' ');
        assertTrue(IntentDetector.looksLikeDiagSearch(fold));
        assertTrue(IntentDetector.looksLikeDiag(
                SpeechInputNormalizer.fold("C'est la première fois ?")
                        .replace('\'', ' ')));
        assertTrue(IntentDetector.looksLikeDiagSearch(
                SpeechInputNormalizer.fold("Ça arrive souvent ?")
                        .replace('\'', ' ')));
    }

    @Test
    public void looksLikeDiagDetail_merdeEtExplique() {
        assertTrue(IntentDetector.looksLikeDiagDetail(
                SpeechInputNormalizer.fold("qu'est-ce qui a merdé hier ?")
                        .replace('\'', ' ').replace('’', ' ')));
        assertTrue(IntentDetector.looksLikeDiagDetail(
                SpeechInputNormalizer.fold("explique l'erreur")
                        .replace('\'', ' ').replace('’', ' ')));
        assertTrue(IntentDetector.looksLikeDiagDetail(
                SpeechInputNormalizer.fold("détaille les problèmes")
                        .replace('\'', ' ').replace('’', ' ')));
        assertTrue(IntentDetector.looksLikeDiag(
                SpeechInputNormalizer.fold("qu'est-ce qui a merdé ?")
                        .replace('\'', ' ').replace('’', ' ')));
        assertFalse(IntentDetector.looksLikeDiagDetail(
                SpeechInputNormalizer.fold("comment tu vas ?")
                        .replace('\'', ' ')));
    }

    @Test
    public void looksLikeDiagDetail_tuAsEuDesProblemesHier() {
        String fold = SpeechInputNormalizer.fold("tu as eu des problèmes hier ?")
                .replace('\'', ' ').replace('’', ' ');
        assertTrue(IntentDetector.looksLikeDiagDetail(fold));
        assertTrue(IntentDetector.looksLikeDiag(fold));
        assertFalse(IntentDetector.looksLikeDiagAnalyze(fold));
        assertTrue(IntentDetector.looksLikeDiagDetail(
                SpeechInputNormalizer.fold("détaille les problèmes")
                        .replace('\'', ' ').replace('’', ' ')));
    }

    @Test
    public void looksLikeDiagDetailFollowUp_deepenPhrases() {
        assertTrue(IntentDetector.looksLikeDiagDetailFollowUp(
                SpeechInputNormalizer.fold("dis m'en plus")
                        .replace('\'', ' ').replace('’', ' ')));
        assertTrue(IntentDetector.looksLikeDiagDetailFollowUp(
                SpeechInputNormalizer.fold("et encore ?")
                        .replace('\'', ' ')));
        assertTrue(IntentDetector.looksLikeDiagDetailFollowUp(
                SpeechInputNormalizer.fold("développe")
                        .replace('\'', ' ')));
        assertTrue(IntentDetector.looksLikeDiagDetailFollowUp(
                SpeechInputNormalizer.fold("tu peux m'en dire plus")
                        .replace('\'', ' ').replace('’', ' ')));
        // Relance seule ≠ diag sans contexte
        assertFalse(IntentDetector.looksLikeDiag(
                SpeechInputNormalizer.fold("dis m'en plus")
                        .replace('\'', ' ').replace('’', ' ')));
        assertFalse(IntentDetector.looksLikeDiagDetail(
                SpeechInputNormalizer.fold("dis m'en plus")
                        .replace('\'', ' ').replace('’', ' ')));
    }

    @Test
    public void analyze_lanceOrionIncludesOrionManager() {
        Context ctx = RuntimeEnvironment.getApplication();
        ContextIntent intent = ContextAnalyzer.analyze(ctx, "Lance Orion");
        assertEquals("orion", intent.intent);
        assertTrue(intent.allowedTools.contains(ToolTag.ORION_MANAGER));
    }

    @Test
    public void analyze_demandeAOrionIncludesOrionCode() {
        Context ctx = RuntimeEnvironment.getApplication();
        ContextIntent intent = ContextAnalyzer.analyze(ctx, "Demande à Orion d'écrire une fonction");
        assertEquals("orion_code", intent.intent);
        assertTrue(intent.allowedTools.contains(ToolTag.ORION_CODE));
        assertTrue(IntentDetector.looksLikeOrionCode(
                SpeechInputNormalizer.fold("code moi un parser JSON")
                        .replace('\'', ' ')));
    }

    @Test
    public void encyclopedic_routesToWikipedia_notTavily() {
        String fold = SpeechInputNormalizer.fold("C'est quoi le coefficient de restitution ?")
                .replace('\'', ' ');
        assertFalse(IntentDetector.needsFreshData(fold));
        assertTrue(IntentDetector.looksLikeEncyclopedic(fold));
        EnumSet<ToolTag> tools = ContextAnalyzer.toolsFor("general", fold);
        assertTrue(tools.contains(ToolTag.WIKIPEDIA));
        assertFalse(tools.contains(ToolTag.SEARCH));
    }

    @Test
    public void freshData_routesToTavily() {
        String fold = SpeechInputNormalizer.fold("Actualité F1 aujourd'hui ?")
                .replace('\'', ' ');
        assertTrue(IntentDetector.needsFreshData(fold));
        assertFalse(IntentDetector.looksLikeEncyclopedic(fold));
        EnumSet<ToolTag> tools = ContextAnalyzer.toolsFor("fresh_data", fold);
        assertTrue(tools.contains(ToolTag.SEARCH));
    }

    @Test
    public void quiAInvente_routesToWikidata() {
        String fold = SpeechInputNormalizer.fold("Qui a inventé le HTML ?")
                .replace('\'', ' ');
        assertTrue(IntentDetector.looksLikeWikidata(fold));
        assertTrue(IntentDetector.looksLikeEncyclopedic(fold));
        EnumSet<ToolTag> tools = ContextAnalyzer.toolsFor("general", fold);
        assertTrue(tools.contains(ToolTag.WIKIDATA));
        assertTrue(tools.contains(ToolTag.WIKIPEDIA));
        assertFalse(tools.contains(ToolTag.SEARCH));
    }

    @Test
    public void ceSoirMatch_needsFreshData() {
        String fold = SpeechInputNormalizer.fold("Résultat du match de ce soir ?")
                .replace('\'', ' ');
        assertTrue(IntentDetector.needsFreshData(fold));
    }
}
