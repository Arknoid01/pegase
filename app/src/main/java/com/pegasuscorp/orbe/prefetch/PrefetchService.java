package com.pegasuscorp.orbe.prefetch;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.diag.DiagBehaviorIndex;
import com.pegasuscorp.orbe.diag.DiagSynthesizer;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.routines.CustomRoutineStore;
import com.pegasuscorp.orbe.tools.HttpJson;
import com.pegasuscorp.orbe.tools.knowledge.TavilySearchService;
import com.pegasuscorp.orbe.tools.knowledge.WeatherTool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Brief du matin : rotation archive + prefetch météo / NASA / boucherie / diag.
 * Tout en arrière-plan, erreurs avalées — jamais de crash si réseau absent.
 */
public final class PrefetchService {

    private static final String PREFS = "prefetch_launch";
    private static final String KEY_LAST_DATE = "last_launch_date";

    private static final String BOUCHERIE_REPORT_URL =
            "https://boucherieamiot.fr/daily_report.json";

    private static final ExecutorService BG = Executors.newSingleThreadExecutor();

    /** Empêche deux run() concurrents le même jour pendant le bootstrap. */
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    /** Tests unitaires : pas d'appels réseau (météo / NASA / boucherie). */
    static volatile boolean skipNetworkForTests = false;

    private PrefetchService() {}

    /** Point d'entrée UI — non bloquant, silencieux. */
    public static void run(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        BG.execute(() -> {
            try {
                runBlocking(app);
            } catch (Exception ignored) {
                // jamais remonter à l'UI
            }
        });
    }

    /**
     * Corps synchrone (tests). {@link Trace#archiveTrace(Context)} est
     * la première action avant tout prefetch.
     */
    static void runBlocking(Context ctx) {
        if (ctx == null) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        try {
            Trace.init(ctx);
            if (!isFirstLaunchToday(ctx)) return;

            // Obligatoire : rotation avant lecture DiagTool / prefetchDiag
            Trace.archiveTrace(ctx);

            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                Future<?> w = pool.submit(() -> safePrefetch(() -> prefetchWeather(ctx)));
                Future<?> n = pool.submit(() -> safePrefetch(() -> prefetchNasa(ctx)));
                Future<?> b = pool.submit(() -> safePrefetch(() -> prefetchBoucherie(ctx)));
                awaitQuiet(w);
                awaitQuiet(n);
                awaitQuiet(b);
            } finally {
                pool.shutdownNow();
            }

            // Séquentiel après le parallèle réseau
            safePrefetch(() -> prefetchDiag(ctx));
            // RAG↔diag : vectorise hésitations / échecs (ns diag), purge > 7j
            safePrefetch(() -> DiagBehaviorIndex.indexFromTraces(ctx));
            // Routines custom après les sources fixes
            safePrefetch(() -> prefetchCustomRoutines(ctx));

            markLaunchedToday(ctx);
            try {
                com.pegasuscorp.orbe.intentions.IntentionEvaluator.onBriefReady(ctx);
            } catch (Exception ignored) {}
        } finally {
            RUNNING.set(false);
        }
    }

    static boolean isFirstLaunchToday(Context ctx) {
        String today = LocalDate.now().toString();
        String last = prefs(ctx).getString(KEY_LAST_DATE, "");
        return !today.equals(last);
    }

    static void markLaunchedToday(Context ctx) {
        prefs(ctx).edit()
                .putString(KEY_LAST_DATE, LocalDate.now().toString())
                .apply();
    }

    /** Visible tests — force un nouveau « premier lancement ». */
    static void clearLaunchMarker(Context ctx) {
        prefs(ctx).edit().remove(KEY_LAST_DATE).apply();
    }

    // ── sources ─────────────────────────────────────────────────────────────

    static void prefetchWeather(Context ctx) {
        if (skipNetworkForTests) return;
        if (PrefetchCache.isFresh(ctx, PrefetchCache.KEY_WEATHER, PrefetchCache.TTL_WEATHER_MS)) {
            return;
        }
        String summary = fetchWeatherSummary(ctx, 2);
        if (!TextUtils.isEmpty(summary)) {
            PrefetchCache.put(ctx, PrefetchCache.KEY_WEATHER, summary);
        }
    }

    static void prefetchNasa(Context ctx) {
        if (skipNetworkForTests) return;
        if (PrefetchCache.isFresh(ctx, PrefetchCache.KEY_NASA, PrefetchCache.TTL_NASA_MS)) {
            return;
        }
        String summary = fetchNasaSummary(ctx);
        if (!TextUtils.isEmpty(summary)) {
            PrefetchCache.put(ctx, PrefetchCache.KEY_NASA, summary);
        }
    }

    static void prefetchBoucherie(Context ctx) {
        if (skipNetworkForTests) return;
        if (PrefetchCache.isFresh(ctx, PrefetchCache.KEY_BOUCHERIE,
                PrefetchCache.TTL_BOUCHERIE_MS)) {
            return;
        }
        String summary = fetchBoucherieSummary();
        if (!TextUtils.isEmpty(summary)) {
            PrefetchCache.put(ctx, PrefetchCache.KEY_BOUCHERIE, summary);
        }
    }

    static void prefetchDiag(Context ctx) {
        String summary = DiagSynthesizer.summarizeArchive(ctx, 1);
        if (!TextUtils.isEmpty(summary)) {
            PrefetchCache.put(ctx, PrefetchCache.KEY_DIAG, summary);
        }
    }

    /**
     * Exécute les routines custom actives après météo/NASA/boucherie/diag.
     * Purge TTL silencieuse au passage.
     */
    static void prefetchCustomRoutines(Context ctx) {
        CustomRoutineStore store = CustomRoutineStore.getInstance(ctx);
        store.purgeExpired();
        StringBuilder combined = new StringBuilder();
        for (CustomRoutineStore.CustomRoutine r : store.listActive()) {
            String text = executeCustomRoutine(ctx, r);
            if (TextUtils.isEmpty(text)) continue;
            PrefetchCache.put(ctx, PrefetchCache.customKey(r.id), text);
            if (combined.length() > 0) combined.append('\n');
            combined.append(text);
        }
        if (combined.length() > 0) {
            PrefetchCache.put(ctx, PrefetchCache.KEY_CUSTOM, combined.toString());
        }
    }

    static String executeCustomRoutine(Context ctx, CustomRoutineStore.CustomRoutine r) {
        if (r == null) return null;
        try {
            switch (r.type) {
                case WEB_SEARCH:
                    if (skipNetworkForTests) return null;
                    TavilySearchService.Bundle bundle =
                            TavilySearchService.search(ctx, r.query);
                    String speech = bundle.fallbackSpeech();
                    if (TextUtils.isEmpty(speech)) return null;
                    String label = TextUtils.isEmpty(r.label) ? r.query : r.label;
                    return label + " — " + oneLine(speech);
                case WEB_PAGE:
                    String url = r.query;
                    if (url.startsWith("www.")) url = "https://" + url;
                    return TextUtils.isEmpty(r.label)
                            ? "Page à ouvrir : " + url
                            : r.label + " (" + url + ")";
                case LOAD_CONTEXT: {
                    String content = ContextualFileStore.getInstance(ctx).load(r.query);
                    if (TextUtils.isEmpty(content)) {
                        return "Contexte « " + r.query + " » introuvable.";
                    }
                    String preview = oneLine(content);
                    if (preview.length() > 160) preview = preview.substring(0, 157) + "…";
                    return "Contexte " + r.query + " : " + preview;
                }
                case REMINDER:
                default:
                    return TextUtils.isEmpty(r.label) ? r.query : r.label;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    // ── fetch réseau (échec = null, jamais throw vers UI) ───────────────────

    static String fetchWeatherSummary(Context ctx, int days) {
        try {
            String savedCoords = ApiKeyStore.getUserCoords(ctx);
            String savedCity = ApiKeyStore.getUserCity(ctx);
            double[] gps = WeatherTool.parseCoords(savedCoords);
            double lat;
            double lon;
            String cityName;

            if (gps != null) {
                lat = gps[0];
                lon = gps[1];
                cityName = savedCity.isEmpty()
                        ? String.format(Locale.FRENCH, "%.2f°, %.2f°", lat, lon)
                        : savedCity;
            } else {
                String searchCity = savedCity.isEmpty() ? "Paris" : savedCity;
                String geoUrl = "https://geocoding-api.open-meteo.com/v1/search"
                        + "?name=" + URLEncoder.encode(searchCity, "UTF-8")
                        + "&count=1&language=fr&format=json";
                JSONObject geo = HttpJson.get(geoUrl);
                JSONArray results = geo.optJSONArray("results");
                if (results == null || results.length() == 0) {
                    return null;
                }
                JSONObject loc = results.getJSONObject(0);
                lat = loc.getDouble("latitude");
                lon = loc.getDouble("longitude");
                cityName = loc.optString("name", searchCity);
            }

            int forecastDays = Math.max(1, Math.min(7, days));
            String weatherUrl = "https://api.open-meteo.com/v1/forecast"
                    + "?latitude=" + lat + "&longitude=" + lon
                    + "&daily=weathercode,temperature_2m_max,temperature_2m_min,"
                    + "precipitation_sum,windspeed_10m_max"
                    + "&current_weather=true"
                    + "&timezone=auto"
                    + "&forecast_days=" + forecastDays;
            JSONObject weather = HttpJson.get(weatherUrl);
            return formatWeather(cityName, forecastDays, weather);
        } catch (Exception e) {
            return null;
        }
    }

    static String fetchNasaSummary(Context ctx) {
        try {
            String key = ApiKeyStore.getNasaApiKey(ctx);
            if (key == null || key.isEmpty()) key = "DEMO_KEY";
            String urlStr = "https://api.nasa.gov/planetary/apod?api_key=" + key;
            JSONObject res = HttpJson.get(urlStr);
            String title = res.optString("title", "Sans titre");
            String explanation = res.optString("explanation", "");
            if (explanation.length() > 400) {
                explanation = explanation.substring(0, 400) + "…";
            }
            return "NASA APOD : " + title
                    + (explanation.isEmpty() ? "" : " — " + explanation);
        } catch (Exception e) {
            return null;
        }
    }

    static String fetchBoucherieSummary() {
        try {
            JSONObject report = HttpJson.get(BOUCHERIE_REPORT_URL);
            int commandes = report.optInt("commandes", report.optInt("orders", -1));
            boolean reception = report.optBoolean("reception_prevue",
                    report.optBoolean("reception", false));
            String fournisseur = report.optString("fournisseur",
                    report.optString("supplier", "")).trim();
            StringBuilder sb = new StringBuilder("Boucherie : ");
            if (commandes >= 0) {
                sb.append(commandes).append(" commande").append(commandes > 1 ? "s" : "")
                        .append(" aujourd'hui");
            } else {
                sb.append("rapport du jour disponible");
            }
            if (reception) {
                sb.append(". Réception");
                if (!fournisseur.isEmpty()) sb.append(' ').append(fournisseur);
                sb.append(" prévue");
            }
            sb.append('.');
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatWeather(String city, int days, JSONObject w) throws Exception {
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
            sb.append(label).append(" à ").append(city).append(" : ")
                    .append(weatherCodeToFrench(code)).append(", ")
                    .append(Math.round(tMin)).append("°C à ")
                    .append(Math.round(tMax)).append("°C");
            if (rain > 1) sb.append(", ").append(Math.round(rain)).append("mm de pluie");
            sb.append(". ");
        }
        return sb.toString().trim();
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

    private static void safePrefetch(Runnable r) {
        try {
            r.run();
        } catch (Exception ignored) {}
    }

    private static void awaitQuiet(Future<?> f) {
        try {
            f.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
