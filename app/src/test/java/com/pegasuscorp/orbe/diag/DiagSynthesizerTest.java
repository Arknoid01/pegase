package com.pegasuscorp.orbe.diag;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.io.File;

import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class DiagSynthesizerTest {

    @Test
    public void summary_empty_returnsNoDataForDate() {
        Trace.init(RuntimeEnvironment.getApplication());
        Trace.clear(RuntimeEnvironment.getApplication());
        DiagDayAggregate.resetForTests(java.time.LocalDate.now());
        String out = DiagSynthesizer.summary(RuntimeEnvironment.getApplication());
        assertTrue(out.startsWith("Aucune donnée pour"));
        assertFalse(out.toLowerCase().contains("bien passé"));
        assertEquals(out, DiagSynthesizer.summarize(RuntimeEnvironment.getApplication()));
    }

    @Test
    public void summary_empty_withAggregate_factualNoDetail() throws Exception {
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        Trace.init(ctx);
        Trace.clear(ctx);
        java.time.LocalDate today = java.time.LocalDate.now();
        new DiagDayAggregate(today.toString(), 61, 61, 2, 18,
                System.currentTimeMillis() - 3_600_000L,
                System.currentTimeMillis()).save();
        String out = DiagSynthesizer.summary(ctx);
        assertTrue(out.contains("détail non conservé") || out.contains("detail non conserve"));
        assertTrue(out.contains("61"));
        assertTrue(out.contains("échec") || out.contains("echec"));
        assertFalse(out.toLowerCase().contains("bien passé"));
        assertFalse(out.contains("Ce n'est pas un RAS"));
    }

    @Test
    public void summary_pastDay_aggregateFirst_noDetail() throws Exception {
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        Trace.init(ctx);
        Trace.clear(ctx);
        DiagDayAggregate.resetForTests(java.time.LocalDate.now());
        java.time.LocalDate past = java.time.LocalDate.now().minusDays(1);
        DiagDayAggregate hist = new DiagDayAggregate(past.toString(), 61, 61, 2, 18,
                System.currentTimeMillis() - 86_400_000L,
                System.currentTimeMillis() - 80_000_000L);
        hist.saveHistory();
        String out = DiagSynthesizer.summary(ctx, past);
        assertTrue(out.contains(DiagDayAggregate.formatDayLabel(past)));
        assertTrue(out.contains("61 messages") || out.contains("61 message"));
        assertTrue(out.contains("détail non conservé"));
        assertFalse(out.startsWith("Aucune donnée"));
    }

    @Test
    public void summary_pastDay_bothEmpty_noData() {
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        Trace.init(ctx);
        Trace.clear(ctx);
        DiagDayAggregate.resetForTests(java.time.LocalDate.now());
        java.time.LocalDate past = java.time.LocalDate.now().minusDays(2);
        File hist = DiagDayAggregate.historyFile(past);
        if (hist != null && hist.exists()) hist.delete();
        File arch = Trace.archiveFile(past);
        if (arch != null && arch.exists()) arch.delete();
        String out = DiagSynthesizer.summary(ctx, past);
        assertTrue(out.startsWith("Aucune donnée pour"));
        assertTrue(out.contains(DiagDayAggregate.formatDayLabel(past)));
    }

    @Test
    public void summary_partialLive_announcesWindowAndReconcile() throws Exception {
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        Trace.init(ctx);
        Trace.clear(ctx);
        long since = System.currentTimeMillis();
        new DiagDayAggregate(java.time.LocalDate.now().toString(), 61, 61, 2, 18,
                since - 8 * 3_600_000L, since).save();
        // Une seule ligne live après « clear »
        File f = Trace.file();
        assertNotNull(f);
        String line = new JSONObject()
                .put("t", since)
                .put("type", "user_message")
                .put("source", "text")
                .put("text", "ping")
                .toString() + "\n";
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String out = DiagSynthesizer.summary(ctx);
        assertTrue(out.contains("Traces disponibles depuis") || out.contains("RAS"));
        assertTrue(out.contains("conservé") && out.contains("61"));
        assertFalse(out.equals(DiagSynthesizer.NOTHING_TO_REPORT_YESTERDAY));
    }

    @Test
    public void summary_empty_saysNothingToDiagnose() {
        // synthesizeSummary (API bas niveau) garde un message session
        String out = DiagSynthesizer.synthesizeSummary(new ArrayList<>(), null);
        assertTrue(out.toLowerCase().contains("pas encore de traces")
                || out.toLowerCase().contains("rien à diagnostiquer")
                || out.contains(DiagSynthesizer.NO_TRACES_AVAILABLE));
    }

    @Test
    public void summary_healthySession_byChannel() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        events.add(user("voice", "Allume la lampe"));
        events.add(user("text", "Salut"));
        events.add(llm("text", "Salut !", 400));
        events.add(bureauAction("open"));
        events.add(bureauEdit(false));
        events.add(llm("bureau", "> OK\n## Notes", 900, true));

        String out = DiagSynthesizer.synthesizeSummary(events, null);
        assertTrue(out.contains("Voix") || out.contains("voix"));
        assertTrue(out.contains("Texte") || out.contains("texte"));
        assertTrue(out.contains("Bureau") || out.contains("bureau"));
        assertTrue(out.toLowerCase().contains("bien passé") || out.contains("0 échec"));
    }

    @Test
    public void hesitations_listsPhantomAndMalformed() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        JSONObject phantom = new JSONObject();
        phantom.put("t", 1);
        phantom.put("type", "phantom_blocked");
        phantom.put("user_request", "Note le lait");
        phantom.put("reply", "C'est noté.");
        events.add(phantom);

        JSONObject bad = new JSONObject();
        bad.put("t", 2);
        bad.put("type", "llm_reply");
        bad.put("malformed_tool", true);
        bad.put("backend", "Groq/test");
        bad.put("text", "{outil cassé");
        events.add(bad);

        String out = DiagSynthesizer.synthesizeHesitations(events);
        assertTrue(out.contains("hésité") || out.contains("fantôme") || out.contains("affirmer"));
        assertTrue(out.toLowerCase().contains("malformé") || out.contains("Groq"));
    }

    @Test
    public void hesitations_enrichedToolHesitation_naturalLanguage() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        JSONObject h = new JSONObject();
        h.put("type", "tool_hesitation");
        h.put("tool", "notepad");
        h.put("reason", "phantom_action");
        h.put("detail", "prose affirmant une action sans outil");
        h.put("user_msg", "note le plan Orion pour le futur");
        events.add(h);

        String out = DiagSynthesizer.synthesizeHesitations(events);
        assertTrue(out.toLowerCase().contains("hésité") || out.contains("hésité"));
        assertTrue(out.contains("notepad"));
        assertTrue(out.contains("Orion") || out.contains("futur") || out.contains("plan"));
    }

    @Test
    public void failures_enrichedToolFailureCtx_http400() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        JSONObject f = new JSONObject();
        f.put("type", "tool_failure_ctx");
        f.put("tool", "llm");
        f.put("reason", "http_400_tool_validation");
        f.put("detail", "HTTP 400 : Failed to validate tool");
        f.put("user_msg", "Allume la lampe");
        events.add(f);

        String out = DiagSynthesizer.synthesizeFailures(events, null);
        assertTrue(out.toLowerCase().contains("échoué") || out.contains("échec")
                || out.contains("400"));
        assertTrue(out.contains("lampe") || out.contains("validation") || out.contains("400"));
    }

    @Test
    public void failures_listsToolEndAndBureauFallback() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        JSONObject fail = new JSONObject();
        fail.put("t", 1);
        fail.put("type", "tool_end");
        fail.put("tool", "notepad");
        fail.put("ok", false);
        fail.put("error", "Précise quoi ajouter.");
        events.add(fail);
        events.add(bureauEdit(true));

        String out = DiagSynthesizer.synthesizeFailures(events, null);
        assertTrue(out.contains("notepad"));
        assertTrue(out.toLowerCase().contains("bureau") || out.toLowerCase().contains("repli"));
    }

    @Test
    public void hesitations_empty_isReassuring() {
        String out = DiagSynthesizer.synthesizeHesitations(new ArrayList<>());
        assertTrue(out.toLowerCase().contains("aucune hésitation")
                || out.toLowerCase().contains("aucune hesitation"));
    }

    @Test
    public void summarizeArchive_empty_returnsNoTracesNotFalseRas() {
        Trace.init(RuntimeEnvironment.getApplication());
        Trace.clear(RuntimeEnvironment.getApplication());
        DiagDayAggregate.resetForTests(java.time.LocalDate.now());
        String out = DiagSynthesizer.summarizeArchive(RuntimeEnvironment.getApplication(), 7);
        assertEquals(DiagSynthesizer.NO_TRACES_AVAILABLE, out);
    }

    @Test
    public void summarizeArchive_cleanArchiveDay_returnsRas() throws Exception {
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        Trace.init(ctx);
        Trace.clear(ctx);

        java.time.LocalDate yesterday = java.time.LocalDate.now().minusDays(1);
        File archive = Trace.archiveFile(yesterday);
        assertNotNull(archive);
        String line = new JSONObject()
                .put("t", System.currentTimeMillis())
                .put("type", "user_message")
                .put("source", "text")
                .put("text", "bonjour")
                .toString() + "\n";
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(archive)) {
            out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        assertEquals(DiagSynthesizer.NOTHING_TO_REPORT_YESTERDAY,
                DiagSynthesizer.summarizeArchive(ctx, 1));
    }

    @Test
    public void summarizeArchive_readsNamedArchiveFiles() throws Exception {
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        Trace.init(ctx);
        Trace.clear(ctx);

        java.time.LocalDate yesterday = java.time.LocalDate.now().minusDays(1);
        File archive = Trace.archiveFile(yesterday);
        assertNotNull(archive);
        String line = new JSONObject()
                .put("t", System.currentTimeMillis())
                .put("type", "tool_end")
                .put("tool", "notepad")
                .put("ok", false)
                .put("error", "text manquant")
                .toString() + "\n";
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(archive)) {
            out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        String synthesized = DiagSynthesizer.summarizeArchive(ctx, 7);
        assertTrue(synthesized.contains(yesterday.toString())
                || synthesized.contains("bilan")
                || synthesized.contains("Bilan")
                || synthesized.contains("Petit bilan"));
        String low = synthesized.toLowerCase();
        assertTrue(low.contains("echec")
                || low.contains("échec")
                || low.contains("raté")
                || low.contains("rate")
                || low.contains("souci")
                || low.contains("friction")
                || low.contains("outil")
                || low.contains("compliqué"));
    }

    @Test
    public void synthesizeWeekly_aggregatesDays() throws Exception {
        List<DiagParser.DayBucket> days = new ArrayList<>();
        List<JSONObject> d1 = new ArrayList<>();
        d1.add(new JSONObject().put("type", "user_message").put("source", "text").put("text", "a"));
        d1.add(new JSONObject().put("type", "tool_end").put("tool", "search").put("ok", false)
                .put("error", "timeout"));
        days.add(new DiagParser.DayBucket("2026-07-14", d1));
        List<JSONObject> d2 = new ArrayList<>();
        d2.add(new JSONObject().put("type", "user_message").put("source", "voice").put("text", "b"));
        days.add(new DiagParser.DayBucket("2026-07-15", d2));

        String out = DiagSynthesizer.synthesizeWeekly(days, 7);
        assertTrue(out.contains("2026-07-14"));
        assertTrue(out.contains("friction") || out.toLowerCase().contains("echec")
                || out.contains("échec"));
    }

    @Test
    public void summary_listsHallucinationsFromReasoningCard() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        events.add(user("text", "Raconte"));
        events.add(llm("text", "On avait essayé Siffle.", 500));
        JSONObject card = new JSONObject();
        card.put("type", "reasoning_card");
        card.put("potentialHallucination", true);
        card.put("hallucination_reason", "Aucune source — on avait essayé");
        card.put("cheminement", "Demande → LLM seul");
        events.add(card);

        String out = DiagSynthesizer.synthesizeSummary(events, null);
        assertTrue(out.toLowerCase().contains("hallucination"));
        assertTrue(out.contains("on avait") || out.contains("Aucune source"));
    }

    @Test
    public void collectCorrectionProblems_includesHallucination() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        JSONObject card = new JSONObject();
        card.put("type", "reasoning_card");
        card.put("potentialHallucination", true);
        card.put("hallucination_reason", "Aucune source — affirmation sur le passé");
        card.put("intent", "Conversation");
        events.add(card);
        List<String> problems = DiagSynthesizer.collectCorrectionProblems(events, null);
        assertFalse(problems.isEmpty());
        assertTrue(problems.get(0).toLowerCase().contains("hallucination")
                || problems.get(0).contains("passé"));
    }

    private static JSONObject user(String source, String text) throws Exception {
        JSONObject o = new JSONObject();
        o.put("t", System.currentTimeMillis());
        o.put("type", "user_message");
        o.put("source", source);
        o.put("text", text);
        return o;
    }

    private static JSONObject llm(String channel, String text, long ms) throws Exception {
        return llm(channel, text, ms, false);
    }

    private static JSONObject llm(String channel, String text, long ms, boolean ephemeral)
            throws Exception {
        JSONObject o = new JSONObject();
        o.put("t", System.currentTimeMillis());
        o.put("type", "llm_reply");
        o.put("text", text);
        o.put("latency_ms", ms);
        o.put("channel", channel);
        if (ephemeral) o.put("ephemeral", true);
        return o;
    }

    @Test
    public void detail_noErrors() {
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        Trace.init(ctx);
        Trace.clear(ctx);
        DiagDayAggregate.resetForTests(java.time.LocalDate.now());
        String out = DiagSynthesizer.detail(ctx, java.time.LocalDate.now());
        assertTrue(out.toLowerCase().contains("aucune erreur"));
        assertFalse(out.contains("traces disponibles"));
        assertFalse(out.contains("événements conservés"));
    }

    @Test
    public void detail_narratesErrorDetails_notStorageStatus() throws Exception {
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        Trace.init(ctx);
        Trace.clear(ctx);
        java.time.LocalDate today = java.time.LocalDate.now();
        long t = System.currentTimeMillis();
        JSONObject entry = new JSONObject()
                .put("t", t)
                .put("type", "tool_end")
                .put("tool", "orion_call")
                .put("message", "timeout Ollama");
        java.util.List<JSONObject> details = new java.util.ArrayList<>();
        details.add(entry);
        new DiagDayAggregate(today.toString(), 10, 8, 1, 0, t, t, details).save();
        String out = DiagSynthesizer.detail(ctx, today);
        assertTrue(out.contains("timeout Ollama"));
        assertTrue(out.contains("orion_call a échoué"));
        assertFalse(out.contains("détail non conservé"));
        assertFalse(out.toLowerCase().contains("événements conservés"));
    }

    @Test
    public void detail_countWithoutList_saysNotKept() throws Exception {
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        Trace.init(ctx);
        Trace.clear(ctx);
        java.time.LocalDate today = java.time.LocalDate.now();
        long t = System.currentTimeMillis();
        new DiagDayAggregate(today.toString(), 10, 8, 0, 3, t, t,
                java.util.Collections.emptyList()).save();
        String out = DiagSynthesizer.detail(ctx, today);
        assertTrue(out.contains("3 erreur"));
        assertTrue(out.contains("détail non conservé"));
    }

    @Test
    public void record_appendsErrorDetails_survivesClear() throws Exception {
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        Trace.init(ctx);
        Trace.clear(ctx);
        DiagDayAggregate.resetForTests(java.time.LocalDate.now());
        JSONObject fail = new JSONObject()
                .put("t", System.currentTimeMillis())
                .put("type", "tool_end")
                .put("ok", false)
                .put("tool", "timer")
                .put("error", "permission refusée");
        DiagDayAggregate.record(fail);
        Trace.clear(ctx);
        DiagDayAggregate agg = DiagDayAggregate.load();
        assertEquals(1, agg.toolFails);
        assertEquals(1, agg.errorDetails.size());
        assertEquals("timer", agg.errorDetails.get(0).optString("tool"));
        String line = DiagDayAggregate.formatErrorDetailLine(agg.errorDetails.get(0));
        assertTrue(line.contains("permission refusée"));
        assertTrue(line.contains("timer a échoué"));
    }

    private static JSONObject bureauAction(String action) throws Exception {
        JSONObject o = new JSONObject();
        o.put("t", System.currentTimeMillis());
        o.put("type", "bureau_action");
        o.put("action", action);
        return o;
    }

    private static JSONObject bureauEdit(boolean fallback) throws Exception {
        JSONObject o = new JSONObject();
        o.put("t", System.currentTimeMillis());
        o.put("type", "bureau_edit");
        o.put("fallback", fallback);
        o.put("markdown_chars", 20);
        o.put("speak", fallback ? "Section ajoutée en local." : "C'est noté.");
        return o;
    }
}
