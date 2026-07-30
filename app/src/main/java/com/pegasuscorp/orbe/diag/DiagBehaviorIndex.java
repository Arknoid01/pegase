package com.pegasuscorp.orbe.diag;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.pegasuscorp.orbe.rag.EmbeddingEngine;
import com.pegasuscorp.orbe.rag.VectorStore;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Vectorise les events {@code tool_hesitation} / {@code tool_failure_ctx}
 * dans {@link VectorStore} namespace {@link VectorStore#NS_DIAG}.
 */
public final class DiagBehaviorIndex {

    private static final String TAG = "DiagBehaviorIndex";
    public static final String NS = VectorStore.NS_DIAG;
    public static final int RETENTION_DAYS = Trace.ARCHIVE_RETENTION_DAYS;
    private static final float MIN_SCORE = 0.42f;
    private static final int TOP_K = 8;

    private static final AtomicBoolean indexing = new AtomicBoolean(false);

    /** Injecteable pour tests (évite ONNX). */
    public interface Embedder {
        float[] embed(String text) throws Exception;
    }

    private DiagBehaviorIndex() {}

    /** Prefetch / matin — silencieux. */
    public static void indexFromTracesAsync(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                indexFromTraces(app);
            } catch (Exception e) {
                Log.w(TAG, "index diag impossible", e);
            }
        }, "diag-rag-index").start();
    }

    /** @return nombre d'events nouvellement indexés */
    public static int indexFromTraces(Context ctx) {
        return indexFromTraces(ctx, null);
    }

    public static int indexFromTraces(Context ctx, Embedder embedder) {
        if (ctx == null) return 0;
        if (!indexing.compareAndSet(false, true)) return 0;
        try {
            Trace.init(ctx);
            VectorStore store = new VectorStore(ctx);
            try {
                store.purgeNamespaceOlderThan(NS, RETENTION_DAYS);
                Embedder eng = embedder != null ? embedder : defaultEmbedder(ctx);
                if (eng == null) return 0;

                List<JSONObject> events = collectDiagEvents(RETENTION_DAYS);
                int created = 0;
                for (JSONObject e : events) {
                    try {
                        if (indexOne(store, eng, e)) created++;
                    } catch (Exception ex) {
                        Log.w(TAG, "index event skip", ex);
                    }
                }
                Log.i(TAG, "Diag RAG : +" + created + " / " + events.size()
                        + " (ns=" + store.size(NS) + ")");
                return created;
            } finally {
                store.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "indexFromTraces", e);
            return 0;
        } finally {
            indexing.set(false);
        }
    }

    static boolean indexOne(VectorStore store, Embedder embedder, JSONObject e)
            throws Exception {
        if (e == null || store == null || embedder == null) return false;
        String type = e.optString("type");
        if (!"tool_hesitation".equals(type) && !"tool_failure_ctx".equals(type)) {
            return false;
        }
        String kind = "tool_hesitation".equals(type) ? "hesitation" : "failure";
        String tool = e.optString("tool", "unknown");
        if (tool.isEmpty()) tool = "unknown";
        String reason = e.optString("reason", "");
        String detail = e.optString("detail", "");
        String userMsg = e.optString("user_msg", "");
        String text = (reason + " " + detail + " " + userMsg).trim();
        if (text.isEmpty()) text = tool + " " + kind;

        long t = e.optLong("t", System.currentTimeMillis());
        LocalDate day = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate();
        String key = eventKey(day, tool, kind, text);
        if (store.hasVector(key)) return false;

        float[] vec = embedder.embed(text);
        String payload = buildPayload(day, kind, tool, reason, detail, userMsg, t);
        store.upsert(key, vec, NS, payload, t);
        return true;
    }

    public static String search(Context ctx, String query) {
        return search(ctx, query, null);
    }

    public static String search(Context ctx, String query, Embedder embedder) {
        if (ctx == null) return "Je n'ai pas accès à mon historique de comportement.";
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return "Précise ce que tu veux chercher dans mon historique "
                    + "(ex. : « tu as déjà hésité sur notepad »).";
        }
        try {
            List<Result> results = searchHits(ctx, q, TOP_K, embedder);
            if (results.isEmpty() && defaultEmbedder(ctx) == null && embedder == null) {
                return "Index comportemental indisponible pour le moment.";
            }
            return synthesizeSearchAnswer(q, toVectorHits(results));
        } catch (Exception e) {
            Log.w(TAG, "search", e);
            return "Je n'ai pas pu fouiller mon historique : "
                    + (e.getMessage() == null ? "erreur" : e.getMessage());
        }
    }

    /**
     * Recherche structurée (bureau réflexion) — topK hits avec résumé court.
     */
    public static List<Result> searchHits(Context ctx, String query, int topK) {
        return searchHits(ctx, query, topK, null);
    }

    public static List<Result> searchHits(Context ctx, String query, int topK,
            Embedder embedder) {
        List<Result> out = new ArrayList<>();
        if (ctx == null) return out;
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return out;
        int k = Math.max(1, Math.min(topK, TOP_K));
        try {
            Trace.init(ctx);
            try {
                indexFromTraces(ctx, embedder);
            } catch (Exception ignored) {}

            Embedder eng = embedder != null ? embedder : defaultEmbedder(ctx);
            if (eng == null) return out;

            VectorStore store = new VectorStore(ctx);
            try {
                store.purgeNamespaceOlderThan(NS, RETENTION_DAYS);
                float[] qv = eng.embed(q);
                List<VectorStore.Hit> hits = store.search(qv, k, MIN_SCORE, NS);
                for (VectorStore.Hit h : hits) {
                    out.add(Result.fromHit(h));
                }
            } finally {
                store.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "searchHits", e);
        }
        return out;
    }

    private static List<VectorStore.Hit> toVectorHits(List<Result> results) {
        List<VectorStore.Hit> raw = new ArrayList<>();
        if (results == null) return raw;
        for (Result r : results) {
            if (r == null) continue;
            raw.add(new VectorStore.Hit(r.memoryKey, r.score, NS, r.payload, r.createdAtMs));
        }
        return raw;
    }

    /** Hit diag pour injection prompt bureau. */
    public static final class Result {
        public final String summary;
        public final float score;
        public final String memoryKey;
        public final String payload;
        public final long createdAtMs;

        public Result(String summary, float score, String memoryKey, String payload,
                long createdAtMs) {
            this.summary = summary == null ? "" : summary;
            this.score = score;
            this.memoryKey = memoryKey == null ? "" : memoryKey;
            this.payload = payload == null ? "" : payload;
            this.createdAtMs = createdAtMs;
        }

        static Result fromHit(VectorStore.Hit h) {
            if (h == null) {
                return new Result("", 0f, "", "", 0L);
            }
            ParsedPayload p = ParsedPayload.parse(h.payload);
            String summary = buildSummary(p, h);
            return new Result(summary, h.score, h.memoryKey, h.payload, h.createdAtMs);
        }

        private static String buildSummary(ParsedPayload p, VectorStore.Hit h) {
            StringBuilder sb = new StringBuilder();
            if (p.tool != null && !p.tool.isEmpty()) {
                sb.append(p.tool);
                if ("hesitation".equals(p.kind)) sb.append(" (hésitation)");
                else if ("failure".equals(p.kind)) sb.append(" (échec)");
                sb.append(" — ");
            }
            if (!TextUtils.isEmpty(p.reason)) {
                sb.append(clip(p.reason, 80));
            } else if (!TextUtils.isEmpty(p.userMsg)) {
                sb.append(clip(p.userMsg, 80));
            } else if (h.payload != null && !h.payload.isEmpty()) {
                sb.append(clip(h.payload, 80));
            } else {
                sb.append("approche déjà essayée");
            }
            return sb.toString().trim();
        }
    }

    /** Synthèse NL — exposée pour tests. */
    public static String synthesizeSearchAnswer(String query, List<VectorStore.Hit> hits) {
        String fold = query == null ? "" : query.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('\'', ' ');
        if (hits == null || hits.isEmpty()) {
            if (looksLikeFirstTime(fold) || looksLikeOften(fold) || looksLikeAlready(fold)) {
                return "Non — je ne trouve rien de similaire dans mes traces des "
                        + RETENTION_DAYS + " derniers jours. "
                        + "Ça a l'air d'être une première (dans cette fenêtre).";
            }
            return "Rien de proche dans mon historique de comportement "
                    + "(7 jours). Tu veux que je regarde autrement ?";
        }

        int n = hits.size();
        VectorStore.Hit top = hits.get(0);
        ParsedPayload p = ParsedPayload.parse(top.payload);
        long daysAgo = daysSince(top.createdAtMs);

        Map<String, Integer> byTool = new LinkedHashMap<>();
        for (VectorStore.Hit h : hits) {
            ParsedPayload x = ParsedPayload.parse(h.payload);
            String tool = x.tool != null ? x.tool : toolFromKey(h.memoryKey);
            byTool.merge(tool, 1, Integer::sum);
        }
        String dominantTool = byTool.isEmpty() ? "outil"
                : byTool.entrySet().iterator().next().getKey();
        for (Map.Entry<String, Integer> en : byTool.entrySet()) {
            if (en.getValue() > byTool.get(dominantTool)) dominantTool = en.getKey();
        }

        if (looksLikeFirstTime(fold)) {
            if (n <= 1) {
                return "Presque — je n'ai qu'un cas proche"
                        + (daysAgo >= 0 ? " (il y a " + daysLabel(daysAgo) + ")" : "")
                        + " sur " + dominantTool + ". Pas un schéma encore.";
            }
            return "Non, ce n'est pas la première fois — "
                    + n + " cas similaires en " + RETENTION_DAYS + " jours"
                    + " (surtout " + dominantTool + ").";
        }

        if (looksLikeOften(fold)) {
            if (n <= 1) {
                return "Rarement — une seule fois en " + RETENTION_DAYS + " jours"
                        + (p.tool != null ? " sur " + p.tool : "") + ".";
            }
            if (n <= 2) {
                return "De temps en temps — " + n + " fois en "
                        + RETENTION_DAYS + " jours, plutôt sur " + dominantTool + ".";
            }
            return "Oui, assez souvent : " + n + " occurrences proches en "
                    + RETENTION_DAYS + " jours, surtout " + dominantTool + ".";
        }

        // Défaut / « déjà eu ce problème »
        StringBuilder sb = new StringBuilder();
        if (n == 1) {
            sb.append("Oui, j'ai un cas similaire");
        } else {
            sb.append("Oui — ").append(n).append(" cas proches");
        }
        if (daysAgo >= 0) {
            sb.append(n == 1 ? " il y a " : ", le plus proche il y a ")
                    .append(daysLabel(daysAgo));
        }
        if (p.tool != null) {
            sb.append(" sur ").append(p.tool);
            if ("hesitation".equals(p.kind)) sb.append(" (hésitation)");
            else if ("failure".equals(p.kind)) sb.append(" (échec)");
        }
        sb.append('.');
        if (!TextUtils.isEmpty(p.userMsg)) {
            sb.append(" Contexte : « ").append(clip(p.userMsg, 70)).append(" ».");
        } else if (!TextUtils.isEmpty(p.reason)) {
            sb.append(" Raison : ").append(clip(p.reason, 60)).append('.');
        }
        if (n >= 3) {
            sb.append(" C'est un pattern récurrent");
            if (dominantTool != null) sb.append(" sur ").append(dominantTool);
            sb.append('.');
        }
        return sb.toString();
    }

    private static boolean looksLikeFirstTime(String fold) {
        return fold.contains("premiere fois")
                || fold.contains("jamais eu")
                || fold.contains("c est nouveau")
                || fold.contains("premiere fois que");
    }

    private static boolean looksLikeOften(String fold) {
        return fold.contains("souvent") || fold.contains("frequent")
                || fold.contains("arrive beaucoup")
                || fold.contains("ca arrive souvent")
                || fold.contains("arrive souvent");
    }

    private static boolean looksLikeAlready(String fold) {
        return fold.contains("deja")
                || fold.contains("eu ce probleme")
                || fold.contains("eu ce souci")
                || fold.contains("pareil")
                || fold.contains("eu le meme");
    }

    private static Embedder defaultEmbedder(Context ctx) {
        try {
            EmbeddingEngine eng = EmbeddingEngine.get(ctx);
            return eng::embed;
        } catch (Exception e) {
            Log.w(TAG, "EmbeddingEngine indisponible", e);
            return null;
        }
    }

    private static List<JSONObject> collectDiagEvents(int daysBack) {
        List<JSONObject> out = new ArrayList<>();
        // loadArchiveDays = trace du jour + archives (pas de doublon)
        List<DiagParser.DayBucket> days = DiagSynthesizer.loadArchiveDays(daysBack);
        for (DiagParser.DayBucket b : days) {
            if (b == null || b.events == null) continue;
            for (JSONObject e : b.events) {
                String type = e.optString("type");
                if ("tool_hesitation".equals(type) || "tool_failure_ctx".equals(type)) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    static String eventKey(LocalDate day, String tool, String kind, String text) {
        String hash = shortHash(text);
        return "diag:" + day + ":" + sanitize(tool) + ":" + kind + ":" + hash;
    }

    private static String buildPayload(LocalDate day, String kind, String tool,
            String reason, String detail, String userMsg, long t) {
        try {
            return new JSONObject()
                    .put("day", day.toString())
                    .put("kind", kind)
                    .put("tool", tool)
                    .put("reason", reason)
                    .put("detail", detail)
                    .put("user_msg", userMsg)
                    .put("t", t)
                    .toString();
        } catch (Exception e) {
            return tool + " " + kind + " " + reason;
        }
    }

    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "unknown";
        return s.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static String shortHash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format(Locale.US, "%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static long daysSince(long epochMs) {
        if (epochMs <= 0) return -1;
        LocalDate then = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate();
        return ChronoUnit.DAYS.between(then, LocalDate.now());
    }

    private static String daysLabel(long daysAgo) {
        if (daysAgo <= 0) return "aujourd'hui";
        if (daysAgo == 1) return "1 jour";
        return daysAgo + " jours";
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim().replace('\n', ' ');
        if (t.length() <= max) return t;
        return t.substring(0, max - 1) + "…";
    }

    private static String toolFromKey(String key) {
        if (key == null) return "outil";
        String[] p = key.split(":");
        return p.length >= 3 ? p[2] : "outil";
    }

    static final class ParsedPayload {
        final String day;
        final String kind;
        final String tool;
        final String reason;
        final String userMsg;

        ParsedPayload(String day, String kind, String tool, String reason, String userMsg) {
            this.day = day;
            this.kind = kind;
            this.tool = tool;
            this.reason = reason;
            this.userMsg = userMsg;
        }

        static ParsedPayload parse(String raw) {
            if (raw == null || raw.isEmpty()) {
                return new ParsedPayload(null, null, null, null, null);
            }
            try {
                JSONObject o = new JSONObject(raw);
                return new ParsedPayload(
                        o.optString("day", null),
                        o.optString("kind", null),
                        o.optString("tool", null),
                        o.optString("reason", null),
                        o.optString("user_msg", null));
            } catch (Exception e) {
                return new ParsedPayload(null, null, null, null, raw);
            }
        }
    }
}
