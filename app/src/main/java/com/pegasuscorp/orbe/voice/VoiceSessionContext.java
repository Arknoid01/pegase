package com.pegasuscorp.orbe.voice;

import com.pegasuscorp.orbe.notepad.NotepadStore;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Mémoire courte de la session vocale : relances (« et demain ? ») et contexte « ajoute ça ».
 */
public final class VoiceSessionContext {

    private static final long FOLLOW_UP_TIMEOUT_MS = 120_000;

    private static VoiceSessionContext instance;

    private String lastIntentHint;
    private String lastToolJson;
    private String lastUserLine;
    private String lastContextLine;
    private long lastIntentAt;

    private VoiceConfirmation.Pending pendingConfirmation;
    private DisambiguationPending pendingDisambiguation;
    private boolean awaitingCorrection;
    private String lastRejectedHeard;

    public static final class DisambiguationPending {
        public final String userLine;
        public final List<VoiceIntentRouter.DisambiguationOption> options;
        public final long createdAt;

        public DisambiguationPending(String userLine,
                List<VoiceIntentRouter.DisambiguationOption> options) {
            this.userLine = userLine;
            this.options = options;
            this.createdAt = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - createdAt > 45_000;
        }
    }

    private VoiceSessionContext() {}

    public static synchronized VoiceSessionContext get() {
        if (instance == null) instance = new VoiceSessionContext();
        return instance;
    }

    public void clear() {
        lastIntentHint = null;
        lastToolJson = null;
        lastUserLine = null;
        lastContextLine = null;
        lastIntentAt = 0;
        pendingConfirmation = null;
        pendingDisambiguation = null;
        awaitingCorrection = false;
        lastRejectedHeard = null;
    }

    public VoiceConfirmation.Pending getPendingConfirmation() {
        if (pendingConfirmation != null && pendingConfirmation.isExpired()) {
            pendingConfirmation = null;
        }
        return pendingConfirmation;
    }

    public void setPendingConfirmation(VoiceConfirmation.Pending pending) {
        this.pendingConfirmation = pending;
        this.awaitingCorrection = false;
    }

    public void clearPendingConfirmation() {
        pendingConfirmation = null;
    }

    public DisambiguationPending getPendingDisambiguation() {
        if (pendingDisambiguation != null && pendingDisambiguation.isExpired()) {
            pendingDisambiguation = null;
        }
        return pendingDisambiguation;
    }

    public void setPendingDisambiguation(DisambiguationPending pending) {
        this.pendingDisambiguation = pending;
        this.pendingConfirmation = null;
        this.awaitingCorrection = false;
    }

    public void clearPendingDisambiguation() {
        pendingDisambiguation = null;
    }

    public boolean isAwaitingCorrection() {
        return awaitingCorrection;
    }

    public void markAwaitingCorrection(String rejectedHeard) {
        awaitingCorrection = true;
        lastRejectedHeard = rejectedHeard;
        pendingConfirmation = null;
    }

    public void clearAwaitingCorrection() {
        awaitingCorrection = false;
        lastRejectedHeard = null;
    }

    public String getLastRejectedHeard() {
        return lastRejectedHeard;
    }

    public void onUserMessage(String line) {
        if (line != null && !line.trim().isEmpty()) {
            lastContextLine = line.trim();
        }
    }

    public void onAssistantReply(String line) {
        if (line != null && !line.trim().isEmpty()) {
            lastContextLine = line.trim();
        }
    }

    public void recordExecution(VoiceIntentRouter.RoutedIntent routed) {
        if (routed == null) return;
        lastIntentHint = routed.intentHint;
        lastToolJson = routed.directToolJson;
        lastUserLine = routed.forLlm;
        lastIntentAt = System.currentTimeMillis();
    }

    public VoiceIntentRouter.RoutedIntent resolveFollowUp(String transcript) {
        if (transcript == null || transcript.trim().isEmpty()) return null;
        if (!hasRecentIntent()) return null;

        String fold = fold(transcript);
        if (fold.isEmpty()) return null;

        VoiceIntentRouter.RoutedIntent notepad = resolveNotepadFollowUp(transcript, fold);
        if (notepad != null) return notepad;

        if ("pareil".equals(fold) || "idem".equals(fold)
                || fold.contains("la meme chose") || fold.contains("encore")) {
            if (lastToolJson != null) {
                return replayLast(transcript);
            }
        }

        if ("météo".equals(lastIntentHint) || lastToolJson != null && lastToolJson.contains("\"weather\"")) {
            return resolveWeatherFollowUp(transcript, fold);
        }

        if ("recherche sport".equals(lastIntentHint) || "sports".equals(lastIntentHint)
                || lastToolJson != null && lastToolJson.contains("\"search\"")
                && lastToolJson.contains("match")) {
            return resolveSportsFollowUp(transcript, fold);
        }

        if ("notifications".equals(lastIntentHint)
                || lastToolJson != null && lastToolJson.contains("\"notifications\"")) {
            return resolveNotificationFollowUp(transcript, fold);
        }

        return null;
    }

    private VoiceIntentRouter.RoutedIntent resolveNotificationFollowUp(String transcript,
                                                                        String fold) {
        try {
            int index = extractNotificationIndex(fold);
            if (index > 0) {
                if (fold.contains("ouvre") || fold.contains("affiche") || fold.contains("montre")) {
                    JSONObject p = new JSONObject().put("action", "open").put("index", index);
                    return new VoiceIntentRouter.RoutedIntent(
                            transcript, toolJson("notifications", p), "notifications",
                            VoiceConfirmation.HIGH_CONFIDENCE, false);
                }
                if (fold.contains("efface") || fold.contains("supprime") || fold.contains("enleve")
                        || fold.contains("enlève") || fold.contains("vide")) {
                    JSONObject p = new JSONObject().put("action", "dismiss").put("index", index);
                    return new VoiceIntentRouter.RoutedIntent(
                            transcript, toolJson("notifications", p), "notifications",
                            VoiceConfirmation.HIGH_CONFIDENCE, false);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int extractNotificationIndex(String fold) {
        if (fold.contains("premiere") || fold.contains("première") || fold.contains("1ere")
                || fold.contains("1ère") || fold.contains("numero 1") || fold.contains("numéro 1")) {
            return 1;
        }
        if (fold.contains("deuxieme") || fold.contains("deuxième") || fold.contains("numero 2")
                || fold.contains("numéro 2")) {
            return 2;
        }
        if (fold.contains("troisieme") || fold.contains("troisième") || fold.contains("numero 3")
                || fold.contains("numéro 3")) {
            return 3;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:numero|numéro)\\s*(\\d+)").matcher(fold);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private static String toolJson(String tool, JSONObject params) {
        try {
            return new JSONObject().put("tool", tool).put("params", params).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private VoiceIntentRouter.RoutedIntent resolveWeatherFollowUp(String transcript, String fold) {
        if (!fold.startsWith("et ") && !fold.contains(" et ")) return null;
        int days = -1;
        if (fold.contains("apres demain") || fold.contains("après demain")) days = 3;
        else if (fold.contains("demain")) days = 2;
        else if (fold.contains("aujourd")) days = 1;
        else if (fold.contains("semaine") || fold.contains("7 jour")) days = 7;
        if (days < 0) return null;
        String json = weatherJson(days);
        if (json == null) return null;
        return new VoiceIntentRouter.RoutedIntent(
                transcript, json, "météo", VoiceConfirmation.HIGH_CONFIDENCE, false);
    }

    private VoiceIntentRouter.RoutedIntent resolveSportsFollowUp(String transcript, String fold) {
        if (fold.contains("prochain") || fold.contains("suivant")) {
            return mutateSportsType(transcript, "next");
        }
        if (fold.contains("dernier") || fold.contains("precedent") || fold.contains("précédent")) {
            return mutateSportsType(transcript, "last");
        }
        String team = VoiceIntentRouter.extractTeamPublic(transcript, fold);
        if (team != null && (fold.contains("et ") || fold.startsWith("et "))) {
            return mutateSportsTeam(transcript, team);
        }
        return null;
    }

    private VoiceIntentRouter.RoutedIntent mutateSportsType(String transcript, String type) {
        String team = teamFromLastSportsSearch();
        if (team != null) {
            String json = VoiceIntentRouter.searchSportsJson(team, type, transcript);
            if (json != null) {
                return new VoiceIntentRouter.RoutedIntent(
                        transcript, json, "recherche sport", VoiceConfirmation.HIGH_CONFIDENCE, false);
            }
        }
        return null;
    }

    private VoiceIntentRouter.RoutedIntent mutateSportsTeam(String transcript, String team) {
        String type = "last";
        if (lastToolJson != null && lastToolJson.contains("prochain")) type = "next";
        String json = VoiceIntentRouter.searchSportsJson(team, type, transcript);
        if (json != null) {
            return new VoiceIntentRouter.RoutedIntent(
                    transcript, json, "recherche sport", 0.8, false);
        }
        return null;
    }

    private String teamFromLastSportsSearch() {
        if (lastToolJson == null) return null;
        try {
            JSONObject root = new JSONObject(lastToolJson);
            if (!"search".equals(root.optString("tool"))) return null;
            String query = root.getJSONObject("params").optString("query", "");
            return query.replaceAll("(?i)\\s+(prochain|dernier)\\s+match.*", "").trim();
        } catch (Exception e) {
            return null;
        }
    }

    private VoiceIntentRouter.RoutedIntent resolveNotepadFollowUp(String transcript, String fold) {
        if (!fold.contains("ajoute ca") && !fold.contains("ajoute ça")
                && !fold.contains("mets ca") && !fold.contains("mets ça")) {
            return null;
        }
        if (!fold.contains("liste") && !fold.contains("bloc") && !fold.contains("faire")) {
            return null;
        }
        String content = lastContextLine;
        if (content == null || content.length() < 3) return null;
        if (content.length() > 120) content = content.substring(0, 117).trim() + "…";
        try {
            JSONObject params = new JSONObject().put("action", "add").put("text", content);
            String json = new JSONObject().put("tool", "notepad").put("params", params).toString();
            return new VoiceIntentRouter.RoutedIntent(
                    transcript, json, "notepad", VoiceConfirmation.HIGH_CONFIDENCE, false);
        } catch (Exception e) {
            return null;
        }
    }

    private VoiceIntentRouter.RoutedIntent replayLast(String transcript) {
        return new VoiceIntentRouter.RoutedIntent(
                transcript, lastToolJson, lastIntentHint,
                VoiceConfirmation.HIGH_CONFIDENCE, false);
    }

    private boolean hasRecentIntent() {
        return lastIntentAt > 0
                && System.currentTimeMillis() - lastIntentAt < FOLLOW_UP_TIMEOUT_MS
                && lastToolJson != null;
    }

    private static String weatherJson(int days) {
        try {
            return new JSONObject()
                    .put("tool", "weather")
                    .put("params", new JSONObject().put("days", days))
                    .toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String fold(String text) {
        if (text == null) return "";
        String n = java.text.Normalizer.normalize(text.toLowerCase(Locale.ROOT),
                java.text.Normalizer.Form.NFD);
        n = n.replaceAll("\\p{M}", "");
        n = n.replace('\'', ' ').replace('’', ' ');
        return n.replaceAll("\\s+", " ").trim();
    }
}
