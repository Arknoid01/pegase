package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.HttpJson;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;

import com.pegasuscorp.orbe.chat.ApiKeyStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Météo via Open-Meteo (gratuit, sans clé, illimité).
 * Coordonnées GPS des paramètres = priorité absolue si renseignées.
 */
public final class WeatherTool implements Tool {

    private static final Pattern COORD_PAIR = Pattern.compile(
            "(-?\\d+(?:[.,]\\d+)?)\\s*[,;]\\s*(-?\\d+(?:[.,]\\d+)?)");

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override public String id() { return "weather"; }

    @Override public ToolTag tag() { return ToolTag.WEATHER; }

    @Override
    public String description() {
        return "weather(city:str, days:int) — Météo actuelle ou prévisions. "
                + "Utilise pour TOUTE question sur la météo, température, pluie, vent, "
                + "\"est-ce qu'il faut un manteau\", \"il va pleuvoir\"... "
                + "Ne mets PAS city si l'utilisateur demande chez lui — le GPS des paramètres est utilisé. "
                + "city = uniquement pour une AUTRE ville explicitement demandée. "
                + "days = nb de jours (1=aujourd'hui, 2=demain, 7=semaine).";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String requestedCity = params.optString("city", "").trim();
        int days = Math.max(1, Math.min(7, params.optInt("days", 1)));

        io.execute(() -> {
            try {
                String savedCoords = ApiKeyStore.getUserCoords(ctx);
                String savedCity = ApiKeyStore.getUserCity(ctx);
                double[] gps = parseCoords(savedCoords);

                double lat;
                double lon;
                String cityName;

                // GPS enregistré = priorité (sauf demande explicite d'une autre ville)
                boolean otherCityRequested = !requestedCity.isEmpty()
                        && !requestedCity.equalsIgnoreCase(savedCity)
                        && !isImplicitHome(requestedCity);

                if (gps != null && !otherCityRequested) {
                    lat = gps[0];
                    lon = gps[1];
                    cityName = savedCity.isEmpty()
                            ? String.format(Locale.FRENCH, "%.2f°, %.2f°", lat, lon)
                            : savedCity;
                } else {
                    String searchCity = !requestedCity.isEmpty()
                            ? requestedCity
                            : (savedCity.isEmpty() ? "Paris" : savedCity);

                    String geoUrl = "https://geocoding-api.open-meteo.com/v1/search"
                            + "?name=" + URLEncoder.encode(searchCity, "UTF-8")
                            + "&count=1&language=fr&format=json";
                    JSONObject geo = HttpJson.get(geoUrl);
                    JSONArray results = geo.optJSONArray("results");

                    if (results == null || results.length() == 0) {
                        if (gps != null) {
                            lat = gps[0];
                            lon = gps[1];
                            cityName = savedCity.isEmpty() ? "chez toi" : savedCity;
                        } else {
                            cb.onError("Ville « " + searchCity + " » introuvable. "
                                    + "Configure tes coordonnées GPS dans les paramètres.");
                            return;
                        }
                    } else {
                        JSONObject loc = results.getJSONObject(0);
                        lat = loc.getDouble("latitude");
                        lon = loc.getDouble("longitude");
                        cityName = loc.optString("name", searchCity);
                    }
                }

                String weatherUrl = "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=" + lat + "&longitude=" + lon
                        + "&daily=weathercode,temperature_2m_max,temperature_2m_min,"
                        + "precipitation_sum,windspeed_10m_max"
                        + "&current_weather=true"
                        + "&timezone=auto"
                        + "&forecast_days=" + days;

                JSONObject weather = HttpJson.get(weatherUrl);
                String summary = buildSummary(cityName, days, weather);
                cb.onSuccess(ToolResult.text(summary));

            } catch (Exception e) {
                cb.onError("Impossible de récupérer la météo : " + e.getMessage());
            }
        });
    }

    /** Parse "46.1083,5.8261" ou "46,1083 ; 5,8261". */
    public static double[] parseCoords(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        Matcher m = COORD_PAIR.matcher(raw.trim());
        if (!m.find()) return null;
        try {
            double lat = parseCoordNumber(m.group(1));
            double lon = parseCoordNumber(m.group(2));
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
            return new double[]{lat, lon};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double parseCoordNumber(String s) {
        return Double.parseDouble(s.trim().replace(',', '.'));
    }

    private static boolean isImplicitHome(String city) {
        String c = city.toLowerCase(Locale.ROOT);
        return c.equals("ici") || c.equals("chez moi") || c.equals("chez-moi")
                || c.equals("ma ville") || c.equals("mon endroit") || c.equals("home");
    }

    private String buildSummary(String city, int days, JSONObject w) throws Exception {
        JSONObject daily = w.getJSONObject("daily");
        JSONArray codes = daily.getJSONArray("weathercode");
        JSONArray maxTemps = daily.getJSONArray("temperature_2m_max");
        JSONArray minTemps = daily.getJSONArray("temperature_2m_min");
        JSONArray precip = daily.getJSONArray("precipitation_sum");
        JSONArray dates = daily.getJSONArray("time");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < days && i < codes.length(); i++) {
            int code = codes.getInt(i);
            double tMax = maxTemps.getDouble(i);
            double tMin = minTemps.getDouble(i);
            double rain = precip.getDouble(i);
            String date = dates.getString(i);

            String label = i == 0 ? "Aujourd'hui" : i == 1 ? "Demain" : "Le " + date;
            String desc = weatherCodeToFrench(code);

            sb.append(label).append(" à ").append(city).append(" : ")
                    .append(desc).append(", ")
                    .append(Math.round(tMin)).append("°C à ").append(Math.round(tMax)).append("°C");
            if (rain > 1) sb.append(", ").append(Math.round(rain)).append("mm de pluie");
            sb.append(". ");
        }
        return sb.toString().trim();
    }

    private String weatherCodeToFrench(int code) {
        if (code == 0) return "ciel dégagé";
        if (code <= 2) return "partiellement nuageux";
        if (code == 3) return "couvert";
        if (code <= 49) return "brouillard";
        if (code <= 57) return "bruine";
        if (code <= 65) return "pluie";
        if (code <= 77) return "neige";
        if (code <= 82) return "averses";
        if (code <= 86) return "averses de neige";
        if (code <= 99) return "orages";
        return "conditions variables";
    }
}
