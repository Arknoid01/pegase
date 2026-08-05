package com.pegasuscorp.orbe.memory;

import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.routing.UserExamplesStore;
import com.pegasuscorp.orbe.tools.ToolTag;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    /**
     * Garde-fou post-daily : general ambigu élargit au set quotidien volontaire,
     * pas à tout le registre (Orion/diag/files gonflent Groq).
     */
    @Test
    public void toolsFor_ambiguousGeneralUsesDailySubsetNotFullRegistry() {
        String fold = SpeechInputNormalizer.fold("Dis-moi des trucs").replace('\'', ' ');
        EnumSet<ToolTag> tools = ContextAnalyzer.toolsFor("general", fold);
        EnumSet<ToolTag> daily = ContextAnalyzer.dailyToolTags();

        assertEquals("general ambigu → exactement le set daily", daily, tools);
        assertNotEquals("ne doit pas renvoyer tout le registre",
                EnumSet.allOf(ToolTag.class), tools);
        assertTrue("daily doit rester un vrai sous-ensemble",
                daily.size() < ToolTag.values().length);

        // Hors chemin quotidien — ne doivent jamais glisser dans general ambigu.
        assertFalse(tools.contains(ToolTag.ORION_MANAGER));
        assertFalse(tools.contains(ToolTag.ORION_CODE));
        assertFalse(tools.contains(ToolTag.GIT_COMMIT));
        assertFalse(tools.contains(ToolTag.DIAG));
        assertFalse(tools.contains(ToolTag.FILES));
        assertFalse(tools.contains(ToolTag.CREATE_FILE));
        // Étape 2 tags : UI / F1 / life / project hors daily ; CALENDAR mort.
        assertFalse(tools.contains(ToolTag.UI));
        assertFalse(tools.contains(ToolTag.F1));
        assertFalse(tools.contains(ToolTag.LIFE_PATTERN));
        assertFalse(tools.contains(ToolTag.PROJECT_OBJECT));
        assertFalse(tools.contains(ToolTag.CALENDAR));

        // Cœur daily toujours présent (régression si quelqu'un vide DAILY_TOOLS).
        assertTrue(tools.contains(ToolTag.NOTEPAD));
        assertTrue(tools.contains(ToolTag.DEVICE));
        assertTrue(tools.contains(ToolTag.WEATHER));
        assertTrue(tools.contains(ToolTag.SEARCH));
        assertTrue(tools.contains(ToolTag.WIKIPEDIA));
        assertTrue(tools.contains(ToolTag.CALL));
        assertTrue(tools.contains(ToolTag.AGENDA));
    }

    @Test
    public void toolsFor_f1PhraseAddsF1NotViaSearchAlone() {
        String fold = SpeechInputNormalizer.fold("Tu en as pensé quoi du GP ?")
                .replace('\'', ' ');
        assertTrue(IntentDetector.looksLikeF1(fold));
        EnumSet<ToolTag> tools = ContextAnalyzer.toolsFor("general", fold);
        assertTrue(tools.contains(ToolTag.F1));
        // Pas d'actualité → pas SEARCH forcé par le seul « f1/gp ».
        assertFalse(tools.contains(ToolTag.SEARCH));
    }

    @Test
    public void toolsFor_uiPhraseAddsUiNotInDaily() {
        String fold = SpeechInputNormalizer.fold("Clique sur le bouton micro")
                .replace('\'', ' ');
        assertTrue(IntentDetector.looksLikeUi(fold));
        EnumSet<ToolTag> tools = ContextAnalyzer.toolsFor("general", fold);
        assertTrue(tools.contains(ToolTag.UI));
        assertFalse(ContextAnalyzer.dailyToolTags().contains(ToolTag.UI));
    }

    @Test
    public void looksLikeUi_realisticIconAndScreenPhrases() {
        String[] mustMatch = {
                "Ouvre l'icône Astronomie",
                "Ouvre l icone",
                "Appuie sur l'icône",
                "Tape sur le bouton",
                "Explique ce qui est a l ecran",
                "Active le micro",
                "Lance la saisie vocale",
        };
        for (String p : mustMatch) {
            String fold = SpeechInputNormalizer.fold(p).replace('\'', ' ');
            assertTrue("looksLikeUi: " + p, IntentDetector.looksLikeUi(fold));
            assertTrue("UI tag: " + p,
                    ContextAnalyzer.toolsFor("general", fold).contains(ToolTag.UI));
        }
        // open_app — ne doit pas tirer UI
        String openApp = SpeechInputNormalizer.fold("Ouvre Cursor").replace('\'', ' ');
        assertFalse(IntentDetector.looksLikeUi(openApp));
        // multi-étape ouvre + clique/tape → UI (pas short-circuit open_app)
        String multi = SpeechInputNormalizer.fold(
                "Ouvre Chrome, clique Rechercher, tape Wikipedia")
                .replace('\'', ' ');
        assertTrue(IntentDetector.looksLikeUi(multi));
        String openTape = SpeechInputNormalizer.fold("Ouvre Chrome et tape Wikipedia")
                .replace('\'', ' ');
        assertTrue(IntentDetector.looksLikeUi(openTape));
        String openSms = SpeechInputNormalizer.fold(
                "Ouvre WhatsApp et envoie un message à papa")
                .replace('\'', ' ');
        assertTrue(IntentDetector.looksLikeUi(openSms));
    }

    @Test
    public void toolsFor_lifePatternSeparateFromBrief() {
        String fold = SpeechInputNormalizer.fold(
                        "Ajoute un rythme ménage de 18h30 à 19h45")
                .replace('\'', ' ');
        assertTrue(IntentDetector.looksLikeLifePattern(fold));
        EnumSet<ToolTag> tools = ContextAnalyzer.toolsFor("general", fold);
        assertTrue(tools.contains(ToolTag.LIFE_PATTERN));
        assertFalse(tools.contains(ToolTag.BRIEF));
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
    public void agendaCetteSemaine_isNotWeeklyDiag() {
        String fold = SpeechInputNormalizer.fold(
                        "J'ai des choses de prévu dans mon agenda cette semaine ?")
                .replace('\'', ' ').replace('’', ' ')
                .replaceAll("\\s+", " ").trim();
        assertTrue(IntentDetector.looksLikeAgenda(fold));
        assertTrue(IntentDetector.looksLikeAgendaQuery(fold));
        assertFalse(IntentDetector.looksLikeDiag(fold));
        assertFalse(IntentDetector.looksLikeWeeklyDiag(fold));
    }

    @Test
    public void bilanCetteSemaine_isStillWeeklyDiag() {
        String fold = SpeechInputNormalizer.fold("Bilan de la semaine")
                .replace('\'', ' ');
        assertTrue(IntentDetector.looksLikeWeeklyDiag(fold));
        assertTrue(IntentDetector.looksLikeDiag(fold));
        String fold2 = SpeechInputNormalizer.fold(
                        "Tu as eu des problèmes cette semaine ?")
                .replace('\'', ' ').replace('’', ' ');
        assertTrue(IntentDetector.looksLikeWeeklyDiag(fold2));
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
        assertTrue(IntentDetector.looksLikeDiagDetailFollowUp(
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
        assertTrue("actualité F1 → SEARCH + compagnon F1", tools.contains(ToolTag.F1));
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

    @Test
    public void analyze_traceRouting_emitsRoutingMatchOnce() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        Trace.init(ctx);
        Trace.clear(ctx);
        UserExamplesStore.getInstance(ctx).addExample("tu as eut des problemes", "diag");
        ContextAnalyzer.analyze(ctx, "t'as eu des soucis ?", true);
        ContextAnalyzer.analyze(ctx, "t'as eu des soucis ?", false);
        ContextAnalyzer.analyze(ctx, "t'as eu des soucis ?", false);
        Trace.flushForTests();
        String jsonl = new String(Files.readAllBytes(Trace.file().toPath()), StandardCharsets.UTF_8);
        int count = 0;
        for (String line : jsonl.split("\n")) {
            if (line.contains("routing_match")) count++;
        }
        assertEquals(1, count);
    }
}
