package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.HttpJson;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.diag.PegaseDiagLog;
import com.pegasuscorp.orbe.intentions.location.LocationLazyGate;
import com.pegasuscorp.orbe.intentions.location.LocationSituationReader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Météo via Open-Meteo (gratuit, sans clé, illimité).
 * Chez soi : GPS live (si frais) → coords paramètres → géocode ville / Paris.
 */
public final class WeatherTool implements Tool {

    private static final String TAG = "WeatherLoc";
    /** Âge max du fix GPS live pour la météo « chez soi ». */
    public static final long LIVE_MAX_AGE_MS = 24L * 60L * 60L * 1000L;

    private static final Pattern COORD_PAIR = Pattern.compile(
            "(-?\\d+(?:[.,]\\d+)?)\\s*[,;]\\s*(-?\\d+(?:[.,]\\d+)?)");

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** Résultat de {@link #resolveLocation}. */
    public static final class ResolvedLocation {
        public final double lat;
        public final double lon;
        public final String cityName;
        public final String source;
        public final String searchCity;
        public final String error;

        public ResolvedLocation(
                double lat,
                double lon,
                String cityName,
                String source,
                String searchCity,
                String error) {
            this.lat = lat;
            this.lon = lon;
            this.cityName = cityName;
            this.source = source;
            this.searchCity = searchCity;
            this.error = error;
        }

        public boolean ok() {
            return error == null;
        }
    }

    @Override public String id() { return "weather"; }

    @Override public ToolTag tag() { return ToolTag.WEATHER; }

    @Override
    public String description() {
        return "weather(city:str, days:int) — Météo actuelle ou prévisions (température, pluie, "
                + "vent, « il faut un manteau ? »). Sans city : position live de l'utilisateur ; "
                + "city seulement pour une AUTRE ville demandée. days : 1=aujourd'hui, 2=demain, "
                + "7=semaine.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        LocationLazyGate.warmForBackgroundUse(ctx);
        String requestedCity = params.optString("city", "").trim();
        int days = Math.max(1, Math.min(7, params.optInt("days", 1)));

        io.execute(() -> {
            try {
                String savedCoords = ApiKeyStore.getUserCoords(ctx);
                String savedCity = ApiKeyStore.getUserCity(ctx);
                long savedCoordsUpdatedMs = ApiKeyStore.getUserCoordsUpdatedMs(ctx);
                double[] savedGps = parseCoords(savedCoords);
                LocationSituationReader.Snapshot live = LocationSituationReader.read(ctx);
                long now = System.currentTimeMillis();

                ResolvedLocation resolved = resolveLocation(ctx, requestedCity, now);
                if (!resolved.ok()) {
                    logLocationDiag(ctx, now, requestedCity, savedCity, savedCoords,
                            savedCoordsUpdatedMs, savedGps, live, null, null, null,
                            resolved.source, resolved.searchCity);
                    cb.onError(resolved.error);
                    return;
                }

                logLocationDiag(ctx, now, requestedCity, savedCity, savedCoords,
                        savedCoordsUpdatedMs, savedGps, live,
                        resolved.lat, resolved.lon, resolved.cityName,
                        resolved.source, resolved.searchCity);

                String weatherUrl = "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=" + resolved.lat + "&longitude=" + resolved.lon
                        + "&daily=weathercode,temperature_2m_max,temperature_2m_min,"
                        + "precipitation_sum,windspeed_10m_max"
                        + "&current_weather=true"
                        + "&timezone=auto"
                        + "&forecast_days=" + days;

                JSONObject weather = HttpJson.get(weatherUrl);
                String summary = buildSummary(resolved.cityName, days, weather);
                cb.onSuccess(ToolResult.text(summary));

            } catch (Exception e) {
                cb.onError("Impossible de récupérer la météo : " + e.getMessage());
            }
        });
    }

    /**
     * Résolution partagée outil + prefetch.
     * Autre ville demandée → géocode. Sinon : live frais → saved coords → géocode.
     */
    public static ResolvedLocation resolveLocation(
            Context ctx, String requestedCity, long nowMs) throws Exception {
        String savedCity = ApiKeyStore.getUserCity(ctx);
        String savedCoords = ApiKeyStore.getUserCoords(ctx);
        double[] savedGps = parseCoords(savedCoords);
        LocationSituationReader.Snapshot live = LocationSituationReader.read(ctx);
        String requested = requestedCity == null ? "" : requestedCity.trim();

        boolean otherCityRequested = !requested.isEmpty()
                && !requested.equalsIgnoreCase(savedCity)
                && !isImplicitHome(requested);

        if (otherCityRequested) {
            return geocodeCity(requested, "geocode_requested_city", savedGps, savedCity);
        }

        return resolveHomeLocation(ctx, live, savedGps, savedCity, nowMs);
    }

    /**
     * Chez soi : GPS live (&lt; 24 h, {@code updatedMs > 0}) → coords paramètres → géocode.
     */
    public static ResolvedLocation resolveHomeLocation(
            Context ctx,
            LocationSituationReader.Snapshot live,
            double[] savedGps,
            String savedCity,
            long nowMs) throws Exception {
        if (isFreshLiveGps(live, nowMs)) {
            String cityName = homeCityLabel(ctx, savedCity, live.lat, live.lon);
            return new ResolvedLocation(
                    live.lat, live.lon, cityName, "live_gps", null, null);
        }
        if (savedGps != null) {
            String cityName = homeCityLabel(ctx, savedCity, savedGps[0], savedGps[1]);
            return new ResolvedLocation(
                    savedGps[0], savedGps[1], cityName, "saved_coords", null, null);
        }
        String searchCity = TextUtils.isEmpty(savedCity) ? "Paris" : savedCity;
        String source = TextUtils.isEmpty(savedCity)
                ? "geocode_fallback_paris"
                : "geocode_saved_city";
        return geocodeCity(searchCity, source, null, savedCity);
    }

    /** Prefetch / home only — préfixe source avec {@code prefetch_} si besoin côté appelant. */
    public static ResolvedLocation resolveHomeLocation(Context ctx, long nowMs) throws Exception {
        LocationSituationReader.Snapshot live = LocationSituationReader.read(ctx);
        double[] savedGps = parseCoords(ApiKeyStore.getUserCoords(ctx));
        String savedCity = ApiKeyStore.getUserCity(ctx);
        return resolveHomeLocation(ctx, live, savedGps, savedCity, nowMs);
    }

    public static boolean isFreshLiveGps(LocationSituationReader.Snapshot live, long nowMs) {
        if (live == null || !live.hasCoords || live.updatedMs <= 0L) return false;
        long age = nowMs - live.updatedMs;
        return age >= 0L && age < LIVE_MAX_AGE_MS;
    }

    private static String homeCityLabel(Context ctx, String savedCity, double lat, double lon) {
        if (!TextUtils.isEmpty(savedCity)) return savedCity.trim();
        String place = LocationSituationReader.getCurrentPlaceLabel(ctx);
        if (!TextUtils.isEmpty(place)) return displayCityName(place.trim());
        return String.format(Locale.FRENCH, "près de toi (%.2f°, %.2f°)", lat, lon);
    }

    /** Libellé lisible dans le chat (évite « maison · Maison » en double). */
    static String displayCityName(String cityName) {
        if (TextUtils.isEmpty(cityName)) return "ici";
        String c = cityName.trim();
        String low = c.toLowerCase(Locale.FRENCH);
        if (low.startsWith("maison") || low.equals("chez moi") || low.equals("chez-moi")) {
            return "chez toi";
        }
        int sep = c.indexOf(" · ");
        if (sep > 0 && c.substring(0, sep).toLowerCase(Locale.FRENCH).equals("maison")) {
            return "chez toi";
        }
        if (sep > 0) {
            String right = c.substring(sep + 3).trim();
            if (!right.isEmpty()) return right;
        }
        return c;
    }

    private static ResolvedLocation geocodeCity(
            String searchCity,
            String source,
            double[] savedGpsFallback,
            String savedCity) throws Exception {
        String geoUrl = "https://geocoding-api.open-meteo.com/v1/search"
                + "?name=" + URLEncoder.encode(searchCity, "UTF-8")
                + "&count=1&language=fr&format=json";
        JSONObject geo = HttpJson.get(geoUrl);
        JSONArray results = geo.optJSONArray("results");
        if (results == null || results.length() == 0) {
            if (savedGpsFallback != null) {
                String cityName = TextUtils.isEmpty(savedCity) ? "chez toi" : savedCity;
                return new ResolvedLocation(
                        savedGpsFallback[0], savedGpsFallback[1], cityName,
                        "saved_coords_after_geocode_miss", searchCity, null);
            }
            return new ResolvedLocation(
                    0, 0, null, "error_city_not_found", searchCity,
                    "Ville « " + searchCity + " » introuvable. "
                            + "Configure tes coordonnées GPS dans les paramètres.");
        }
        JSONObject loc = results.getJSONObject(0);
        double lat = loc.getDouble("latitude");
        double lon = loc.getDouble("longitude");
        String cityName = loc.optString("name", searchCity);
        return new ResolvedLocation(lat, lon, cityName, source, searchCity, null);
    }

    /** Diagnostique source + fraîcheur (params vs GPS live). */
    public static void logLocationDiag(
            Context ctx,
            long nowMs,
            String requestedCity,
            String savedCity,
            String savedCoordsRaw,
            long savedCoordsUpdatedMs,
            double[] savedGps,
            LocationSituationReader.Snapshot live,
            Double usedLat,
            Double usedLon,
            String usedCity,
            String source,
            String searchCity) {
        try {
            long savedAgeMs = savedCoordsUpdatedMs > 0L ? Math.max(0L, nowMs - savedCoordsUpdatedMs) : -1L;
            boolean trueLive = live != null && live.hasCoords && live.updatedMs > 0L;
            long liveAgeMs = trueLive ? Math.max(0L, nowMs - live.updatedMs) : -1L;
            Float deltaM = null;
            if (usedLat != null && usedLon != null && trueLive) {
                deltaM = LocationSituationReader.distanceM(usedLat, usedLon, live.lat, live.lon);
            }
            boolean usedLive = source != null && source.contains("live_gps");

            JSONObject fields = new JSONObject();
            fields.put("source", source);
            fields.put("requested_city", requestedCity == null ? "" : requestedCity);
            fields.put("saved_city", savedCity == null ? "" : savedCity);
            fields.put("saved_coords", savedCoordsRaw == null ? "" : savedCoordsRaw);
            fields.put("saved_coords_updated_ms", savedCoordsUpdatedMs);
            fields.put("saved_coords_age_ms", savedAgeMs);
            fields.put("saved_coords_freshness",
                    savedAgeMs < 0 ? "unknown_never_stamped"
                            : (savedAgeMs < 3_600_000L ? "fresh_lt_1h"
                            : (savedAgeMs < 86_400_000L ? "stale_lt_1d" : "stale_gt_1d")));
            fields.put("has_saved_gps", savedGps != null);
            if (usedLat != null) fields.put("used_lat", usedLat);
            if (usedLon != null) fields.put("used_lon", usedLon);
            if (usedCity != null) fields.put("used_city", usedCity);
            if (searchCity != null) fields.put("search_city", searchCity);
            fields.put("live_gps_available", trueLive && isFreshLiveGps(live, nowMs));
            fields.put("live_gps_stale_or_present", trueLive);
            if (trueLive) {
                fields.put("live_lat", live.lat);
                fields.put("live_lon", live.lon);
                fields.put("live_updated_ms", live.updatedMs);
                fields.put("live_age_ms", liveAgeMs);
            }
            fields.put("live_used_by_weather", usedLive);
            if (deltaM != null) {
                fields.put("delta_used_vs_live_m", Math.round(deltaM));
            }
            fields.put("note", usedLive
                    ? "weather_uses_live_gps"
                    : ("weather_source=" + (source == null ? "?" : source)));

            Log.i(TAG, "weather_location source=" + source
                    + " used=" + (usedCity != null ? usedCity : "?")
                    + " lat=" + usedLat + " lon=" + usedLon
                    + " saved_age_ms=" + savedAgeMs
                    + " live_age_ms=" + liveAgeMs
                    + (deltaM != null ? " delta_m=" + Math.round(deltaM) : "")
                    + " live_used=" + usedLive);
            PegaseDiagLog.kws(ctx, "weather_location", fields);
        } catch (Exception e) {
            Log.w(TAG, "logLocationDiag failed", e);
        }
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
        return formatSummary(displayCityName(city), days, w);
    }

    /**
     * Texte affiché dans le chat (surtout mode local qui recopie l'outil tel quel).
     * Multi-jours : lignes à puces, dates en français, pas de ISO brut.
     */
    static String formatSummary(String city, int days, JSONObject w) throws Exception {
        JSONObject daily = w.getJSONObject("daily");
        JSONArray codes = daily.getJSONArray("weathercode");
        JSONArray maxTemps = daily.getJSONArray("temperature_2m_max");
        JSONArray minTemps = daily.getJSONArray("temperature_2m_min");
        JSONArray precip = daily.getJSONArray("precipitation_sum");
        JSONArray dates = daily.getJSONArray("time");

        String place = displayCityName(city != null ? city : "ici");
        boolean multi = days > 1;
        StringBuilder sb = new StringBuilder();
        if (multi) {
            sb.append("Météo ").append(place).append(" — ").append(days).append(" jours :\n\n");
            if (!forecastHasSnow(codes, days)) {
                sb.append("Pas de neige prévue sur cette période.\n\n");
            }
        }

        for (int i = 0; i < days && i < codes.length(); i++) {
            int code = codes.getInt(i);
            double tMax = maxTemps.getDouble(i);
            double tMin = minTemps.getDouble(i);
            double rain = precip.getDouble(i);
            String date = dates.getString(i);
            String label = dayLabel(i, date);
            String desc = weatherCodeToFrench(code);

            if (multi) {
                if (i > 0) sb.append('\n');
                sb.append("• ").append(label).append(" — ").append(desc).append(", ")
                        .append(Math.round(tMin)).append("–").append(Math.round(tMax)).append(" °C");
                if (rain > 1) sb.append(", ").append(Math.round(rain)).append(" mm");
            } else {
                sb.append(label).append(" à ").append(place).append(" : ")
                        .append(desc).append(", ")
                        .append(Math.round(tMin)).append("°C à ").append(Math.round(tMax)).append("°C");
                if (rain > 1) sb.append(", ").append(Math.round(rain)).append(" mm de pluie");
                sb.append('.');
            }
        }
        return sb.toString().trim();
    }

    static String dayLabel(int index, String isoDate) {
        if (index == 0) return "Aujourd'hui";
        if (index == 1) return "Demain";
        if (isoDate != null && !isoDate.isEmpty()) {
            try {
                LocalDate d = LocalDate.parse(isoDate);
                String wd = d.format(DateTimeFormatter.ofPattern("EEEE", Locale.FRENCH));
                String dm = d.format(DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH));
                if (!wd.isEmpty()) {
                    return Character.toUpperCase(wd.charAt(0)) + wd.substring(1) + " " + dm;
                }
            } catch (DateTimeParseException ignored) {
            }
        }
        return "Le " + (isoDate != null ? isoDate : "?");
    }

    static boolean forecastHasSnow(JSONArray codes, int days) {
        if (codes == null) return false;
        int n = Math.min(days, codes.length());
        for (int i = 0; i < n; i++) {
            int code = codes.optInt(i, -1);
            if (code >= 71 && code <= 77) return true;
            if (code >= 85 && code <= 86) return true;
        }
        return false;
    }

    private static String weatherCodeToFrench(int code) {
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
