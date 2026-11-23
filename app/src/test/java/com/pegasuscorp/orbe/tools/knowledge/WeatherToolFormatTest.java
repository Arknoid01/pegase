package com.pegasuscorp.orbe.tools.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class WeatherToolFormatTest {

    @Test public void displayCityName_maison_doublon() {
        assertEquals("chez toi", WeatherTool.displayCityName("maison · Maison"));
        assertEquals("Lyon", WeatherTool.displayCityName("Lyon"));
    }

    @Test public void formatSummary_multi_lignes_sans_iso() throws Exception {
        JSONObject w = fixtureWeek();
        String out = WeatherTool.formatSummary("maison · Maison", 7, w);
        assertTrue(out.startsWith("Météo chez toi"));
        assertTrue(out.contains("Pas de neige prévue"));
        assertFalse(out.contains("2026-11-25"));
        assertTrue(out.contains("• Aujourd'hui"));
        assertTrue(out.contains("• Demain"));
        assertTrue(out.contains("\n"));
    }

    @Test public void forecastHasSnow_detecte_code_neige() throws Exception {
        JSONArray codes = new JSONArray(new int[]{61, 71, 3});
        assertTrue(WeatherTool.forecastHasSnow(codes, 3));
        assertFalse(WeatherTool.forecastHasSnow(new JSONArray(new int[]{61, 3, 3}), 3));
    }

    private static JSONObject fixtureWeek() throws Exception {
        JSONObject daily = new JSONObject()
                .put("weathercode", new JSONArray(new int[]{61, 61, 61, 61, 3, 45, 61}))
                .put("temperature_2m_max", new JSONArray(new double[]{7, 7, 7, 7, 6, 5, 6}))
                .put("temperature_2m_min", new JSONArray(new double[]{3, 1, 4, 4, 0, 0, 1}))
                .put("precipitation_sum", new JSONArray(new double[]{15, 1, 22, 2, 0, 0, 2}))
                .put("time", new JSONArray(new String[]{
                        "2026-11-23", "2026-11-24", "2026-11-25", "2026-11-26",
                        "2026-11-27", "2026-11-28", "2026-11-29"}));
        return new JSONObject().put("daily", daily);
    }
}