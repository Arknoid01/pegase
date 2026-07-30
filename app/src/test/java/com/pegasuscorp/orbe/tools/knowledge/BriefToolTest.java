package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.ToolCallback;

import com.pegasuscorp.orbe.tools.ToolResult;

import android.content.Context;

import com.pegasuscorp.orbe.fs.PegaseFileSystem;
import com.pegasuscorp.orbe.prefetch.PrefetchCache;
import com.pegasuscorp.orbe.routines.CustomRoutineStore;
import com.pegasuscorp.orbe.voice.VoiceIntentRouter;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BriefToolTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        PrefetchCache.clear(ctx);
        CustomRoutineStore.resetInstanceForTests();
        PegaseFileSystem.resetInstanceForTests();
        com.pegasuscorp.orbe.chat.FallbackChatBackend.setOnFallbackBackendForTests(null);
        com.pegasuscorp.orbe.chat.MultiProviderBackend.setOnFallbackBackendForTests(null);
        wipeRoutines();
    }

    @After
    public void tearDown() {
        PrefetchCache.clear(ctx);
        wipeRoutines();
        CustomRoutineStore.resetInstanceForTests();
        PegaseFileSystem.resetInstanceForTests();
        com.pegasuscorp.orbe.chat.FallbackChatBackend.setOnFallbackBackendForTests(null);
        com.pegasuscorp.orbe.chat.MultiProviderBackend.setOnFallbackBackendForTests(null);
    }

    private void wipeRoutines() {
        CustomRoutineStore store = CustomRoutineStore.getInstance(ctx);
        for (CustomRoutineStore.CustomRoutine r :
                new java.util.ArrayList<>(store.listAll())) {
            store.remove(r.id);
        }
    }

    @Test
    public void composeBrief_fullCache_ordersSources() {
        PrefetchCache.put(ctx, PrefetchCache.KEY_WEATHER, "Orages cet après-midi, 21 à 34°C.");
        PrefetchCache.put(ctx, PrefetchCache.KEY_BOUCHERIE, "3 commandes aujourd'hui.");
        PrefetchCache.put(ctx, PrefetchCache.KEY_NASA, "NASA APOD : nébuleuse d'Orion.");
        PrefetchCache.put(ctx, PrefetchCache.KEY_DIAG, "Hier impeccable — 0 problème.");

        CustomRoutineStore.CustomRoutine r = CustomRoutineStore.getInstance(ctx)
                .add(CustomRoutineStore.Type.REMINDER, "appeler le Saloir", "Rappel : Saloir", null);
        PrefetchCache.put(ctx, PrefetchCache.customKey(r.id), "Rappel : Saloir");

        String brief = BriefTool.composeBrief(ctx);
        int w = brief.indexOf("Orages");
        int b = brief.indexOf("commandes");
        int n = brief.indexOf("NASA");
        int d = brief.indexOf("impeccable");
        int c = brief.indexOf("Saloir");
        assertTrue(brief, w >= 0 && b > w && n > b && d > n && c > d);
    }

    @Test
    public void composeBrief_partialCache_skipsMissingSilently() {
        PrefetchCache.put(ctx, PrefetchCache.KEY_WEATHER, "Ciel dégagé à Lyon.");
        PrefetchCache.put(ctx, PrefetchCache.KEY_DIAG, "Hier 1 hésitation notepad.");

        String brief = BriefTool.composeBrief(ctx);
        assertTrue(brief.contains("Lyon") || brief.contains("dégagé"));
        assertTrue(brief.contains("hésitation") || brief.contains("notepad"));
        assertFalse(brief.toLowerCase().contains("nasa apod"));
        assertFalse(brief.toLowerCase().contains("boucherie"));
    }

    @Test
    public void composeBrief_fallbackBackend_skipsDiagSilently() {
        PrefetchCache.put(ctx, PrefetchCache.KEY_WEATHER, "Ciel dégagé à Lyon.");
        PrefetchCache.put(ctx, PrefetchCache.KEY_DIAG, "Hier 1 hésitation notepad.");
        com.pegasuscorp.orbe.chat.FallbackChatBackend.setOnFallbackBackendForTests(true);

        assertTrue(BriefTool.isOnFallbackBackend(ctx));
        String brief = BriefTool.composeBrief(ctx);
        assertTrue(brief.contains("Lyon") || brief.contains("dégagé"));
        assertFalse(brief.contains("hésitation"));
        assertFalse(brief.contains("notepad"));
        assertFalse(brief.toLowerCase().contains("diag"));
    }

    @Test
    public void emptyParams_afterRecentBrief_returnsCacheProseNotNewBrief() throws Exception {
        PrefetchCache.put(ctx, PrefetchCache.KEY_WEATHER, "Pluie ce matin.");
        PrefetchCache.put(ctx, PrefetchCache.KEY_NASA, "NASA APOD : Orion.");
        BriefTool.composeBrief(ctx);
        assertTrue(BriefTool.hasRecentBrief(ctx));

        AtomicReference<String> ok = new AtomicReference<>();
        new BriefTool().execute(ctx, new JSONObject(), new ToolCallback() {
            @Override public void onSuccess(ToolResult result) {
                ok.set(result != null ? result.text : null);
            }
            @Override public void onError(String message) {}
            @Override
            public void onConfirmNeeded(String question, Runnable onYes, Runnable onNo) {
                if (onYes != null) onYes.run();
            }
        });
        assertNotNull(ok.get());
        assertTrue(ok.get().contains("Pluie") || ok.get().contains("Orion")
                || ok.get().contains("détail") || ok.get().contains("detail"));
    }

    @Test
    public void description_mentionsNoRepeatedBriefOnDetail() {
        String d = new BriefTool().description().toLowerCase();
        assertTrue(d.contains("plus de détail") || d.contains("plus de detail")
                || d.contains("ne pas rappeler"));
        assertTrue(d.contains("prose") || d.contains("cache"));
    }

    @Test
    public void composeBrief_unreliableDiag_skippedSilently() {
        PrefetchCache.put(ctx, PrefetchCache.KEY_WEATHER, "Ciel dégagé à Lyon.");
        PrefetchCache.put(ctx, PrefetchCache.KEY_DIAG, "pas d'archives — backend fallback");

        String brief = BriefTool.composeBrief(ctx);
        assertTrue(brief.contains("Lyon") || brief.contains("dégagé"));
        assertFalse(brief.toLowerCase().contains("archives"));
        assertFalse(brief.toLowerCase().contains("fallback"));
        assertFalse(BriefTool.isReliableDiag("pas d'archives"));
        assertFalse(BriefTool.isReliableDiag("backend fallback"));
        assertTrue(BriefTool.isReliableDiag(
                "Rien à signaler hier — tout s'est bien passé."));
    }

    @Test
    public void composeBrief_rasDiag_included() {
        PrefetchCache.put(ctx, PrefetchCache.KEY_WEATHER, "Soleil.");
        PrefetchCache.put(ctx, PrefetchCache.KEY_DIAG,
                "Rien à signaler hier — tout s'est bien passé.");
        String brief = BriefTool.composeBrief(ctx);
        assertTrue(brief.contains("Soleil"));
        assertTrue(brief.contains("Rien à signaler"));
    }

    @Test
    public void executeDetail_returnsCacheProse() throws Exception {
        PrefetchCache.put(ctx, PrefetchCache.KEY_WEATHER, "Pluie ce matin.");
        PrefetchCache.put(ctx, PrefetchCache.KEY_DIAG, "Hier 1 hésitation notepad.");
        AtomicReference<String> ok = new AtomicReference<>();
        new BriefTool().execute(ctx, new JSONObject().put("action", "detail"),
                new ToolCallback() {
                    @Override public void onSuccess(ToolResult result) {
                        ok.set(result != null ? result.text : null);
                    }
                    @Override public void onError(String message) {}
                    @Override
                    public void onConfirmNeeded(String question, Runnable onYes, Runnable onNo) {
                        if (onYes != null) onYes.run();
                    }
                });
        assertNotNull(ok.get());
        assertTrue(ok.get().contains("Pluie"));
        assertTrue(ok.get().contains("hésitation") || ok.get().contains("notepad"));
    }

    @Test
    public void voiceRouter_plusDeDetail_routesToBriefDetail() {
        VoiceIntentRouter.RoutedIntent r =
                VoiceIntentRouter.analyze(ctx, "plus de détail");
        assertNotNull(r.directToolJson);
        assertTrue(r.directToolJson.contains("\"brief\""));
        assertTrue(r.directToolJson.contains("detail"));
    }

    @Test
    public void executeBrief_returnsCacheText() throws Exception {
        PrefetchCache.put(ctx, PrefetchCache.KEY_WEATHER, "Pluie ce matin.");
        AtomicReference<String> ok = new AtomicReference<>();
        AtomicReference<String> err = new AtomicReference<>();
        new BriefTool().execute(ctx, new JSONObject().put("action", "brief"),
                new ToolCallback() {
                    @Override public void onSuccess(ToolResult result) {
                        ok.set(result != null ? result.text : null);
                    }
                    @Override public void onError(String message) {
                        err.set(message);
                    }
                    @Override
                    public void onConfirmNeeded(String question, Runnable onYes, Runnable onNo) {
                        if (onYes != null) onYes.run();
                    }
                });
        assertNull(err.get());
        assertNotNull(ok.get());
        assertTrue(ok.get().contains("Pluie"));
    }

    @Test
    public void voiceAdd_createsWebSearchRoutine() {
        String phrase = "ajoute à ma routine du matin : cherche les résultats F1";
        CustomRoutineStore.CustomRoutine created =
                CustomRoutineStore.getInstance(ctx).addFromVoice(phrase);
        assertNotNull(created);
        assertEquals(CustomRoutineStore.Type.WEB_SEARCH, created.type);
        assertTrue(created.query.toLowerCase().contains("f1")
                || created.query.toLowerCase().contains("résultats")
                || created.query.toLowerCase().contains("resultats"));
        assertTrue(created.active);
    }

    @Test
    public void ttlExpired_purgedOnLoad() {
        long eightDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8);
        CustomRoutineStore.getInstance(ctx).add(
                CustomRoutineStore.Type.REMINDER, "ancien", "Rappel ancien", 7, eightDaysAgo);
        CustomRoutineStore.getInstance(ctx).add(
                CustomRoutineStore.Type.REMINDER, "récent", "Rappel récent", null);

        assertEquals(1, CustomRoutineStore.getInstance(ctx).purgeExpired());
        assertEquals(1, CustomRoutineStore.getInstance(ctx).listAll().size());
        assertEquals("Rappel récent",
                CustomRoutineStore.getInstance(ctx).listAll().get(0).label);
    }

    @Test
    public void voiceRouter_briefDuMatin_routesToBriefTool() {
        VoiceIntentRouter.RoutedIntent r =
                VoiceIntentRouter.analyze(ctx, "brief du matin");
        assertNotNull(r.directToolJson);
        assertTrue(r.directToolJson.contains("\"brief\""));
        assertTrue(r.directToolJson.contains("brief"));
    }

    @Test
    public void voiceRouter_ajouteRoutine_routesToBriefAdd() {
        VoiceIntentRouter.RoutedIntent r = VoiceIntentRouter.analyze(ctx,
                "ajoute à ma routine du matin : cherche les résultats F1");
        assertNotNull(r.directToolJson);
        assertTrue(r.directToolJson.contains("brief"));
        assertTrue(r.directToolJson.contains("add")
                || r.directToolJson.contains("utterance"));
    }

    @Test
    public void resolveAction_detailFromQuery() throws Exception {
        assertEquals("detail", BriefTool.resolveAction(
                new JSONObject().put("query", "plus de détail")));
        assertEquals("add", BriefTool.resolveAction(
                new JSONObject().put("utterance", "ajoute une routine météo")));
        assertEquals("brief", BriefTool.resolveAction(new JSONObject()));
    }

    @Test
    public void looksLikeBriefDetailFollowUp_developpeRequiresBrief() {
        assertTrue(BriefTool.looksLikeBriefDetailFollowUp("developpe le brief"));
        assertTrue(BriefTool.looksLikeBriefDetailFollowUp("plus de detail"));
        assertFalse(BriefTool.looksLikeBriefDetailFollowUp(
                "je veux developper un jeu sur navigateur"));
        assertFalse(BriefTool.looksLikeBriefDetailFollowUp(
                "force a chaque fois a gerer des commandes"));
    }
}
