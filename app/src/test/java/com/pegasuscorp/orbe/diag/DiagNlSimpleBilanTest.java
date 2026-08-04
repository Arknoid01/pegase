package com.pegasuscorp.orbe.diag;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class DiagNlSimpleBilanTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        Trace.init(ctx);
        Trace.clear(ctx);
    }

    @Test
    public void synthesizeWeeklySimple_groupsCopilotFailsByApp() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        events.add(copilot(1, "matcher_miss", "com.whatsapp", "Envoyer"));
        events.add(copilot(2, "matcher_miss", "com.whatsapp", "Micro"));
        events.add(copilot(3, "matcher_miss", "com.android.chrome", "OK"));
        List<DiagParser.DayBucket> days = Collections.singletonList(
                new DiagParser.DayBucket("2026-08-01", events));

        String out = DiagNlGenerator.synthesizeWeeklySimple(ctx, days, 1);
        assertTrue(out.contains("WhatsApp") || out.toLowerCase().contains("whatsapp"));
        assertTrue(out.contains("surtout") || out.contains("cliquer")
                || out.contains("souci"));
        assertFalse(out.toLowerCase().contains("hallucination"));
        assertFalse(out.toLowerCase().contains("action fantôme"));
    }

    @Test
    public void synthesizeWeeklySimple_mentionsKnownCorrection() throws Exception {
        CorrectionsStore.mergePending(ctx,
                Collections.singletonList("Copilote clic WhatsApp qui rate"));
        List<JSONObject> events = new ArrayList<>();
        events.add(copilot(1, "matcher_miss", "com.whatsapp", "Envoyer"));
        List<DiagParser.DayBucket> days = Collections.singletonList(
                new DiagParser.DayBucket("2026-08-01", events));

        String out = DiagNlGenerator.synthesizeWeeklySimple(ctx, days, 1);
        assertTrue(out.contains("Déjà noté") || out.contains("pas encore réglé"));
    }

    @Test
    public void synthesizeSummarySimple_avoidsTechJargon() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        events.add(new JSONObject().put("type", "user_message").put("source", "text")
                .put("text", "hi"));
        events.add(new JSONObject().put("type", "phantom_blocked")
                .put("user_request", "note ça"));
        String out = DiagNlGenerator.synthesizeSummarySimple(ctx, events, null);
        assertFalse(out.toLowerCase().contains("action fantôme"));
        assertTrue(out.toLowerCase().contains("fausse")
                || out.toLowerCase().contains("annonce")
                || out.toLowerCase().contains("souci")
                || out.toLowerCase().contains("bilan"));
    }

    @Test
    public void appLabel_mapsCommonPackages() {
        assertEquals("WhatsApp", DiagNlGenerator.appLabel("com.whatsapp"));
        assertEquals("Chrome", DiagNlGenerator.appLabel("com.android.chrome"));
    }

    private static JSONObject copilot(long t, String kind, String pkg, String target)
            throws Exception {
        return new JSONObject()
                .put("t", t)
                .put("type", "copilot_ui")
                .put("kind", kind)
                .put("pkg", pkg)
                .put("target", target);
    }
}
