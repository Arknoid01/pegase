package com.pegasuscorp.orbe.pcshell;

import org.json.JSONObject;

/**
 * Commande shell enregistrée — exécutable uniquement via tap manuel (Phase 1).
 */
public final class SavedCommand {

    public final String id;
    public final String slug;
    public final String label;
    public final String command;
    /** Timeout SSH optionnel (secondes) — {@code null} = heuristique / défaut. */
    public final Integer timeoutSeconds;
    public final long createdAtMs;
    public final Long lastRunAtMs;
    public final Integer lastExitCode;

    public SavedCommand(String id, String slug, String label, String command, long createdAtMs,
            Long lastRunAtMs, Integer lastExitCode) {
        this(id, slug, label, command, null, createdAtMs, lastRunAtMs, lastExitCode);
    }

    public SavedCommand(String id, String slug, String label, String command,
            Integer timeoutSeconds, long createdAtMs, Long lastRunAtMs, Integer lastExitCode) {
        this.id = id != null ? id : "";
        this.slug = slug != null ? slug.trim() : "";
        this.label = label != null ? label : "";
        this.command = command != null ? command : "";
        this.timeoutSeconds = normalizeTimeout(timeoutSeconds);
        this.createdAtMs = createdAtMs;
        this.lastRunAtMs = lastRunAtMs;
        this.lastExitCode = lastExitCode;
    }

    private static Integer normalizeTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null || timeoutSeconds <= 0) return null;
        return Math.min(timeoutSeconds, PcShellPrefs.MAX_TIMEOUT_SEC);
    }

    public SavedCommand withRunResult(long runAtMs, int exitCode) {
        return new SavedCommand(id, slug, label, command, timeoutSeconds,
                createdAtMs, runAtMs, exitCode);
    }

    public SavedCommand withTimeout(int timeoutSec) {
        return new SavedCommand(id, slug, label, command, timeoutSec,
                createdAtMs, lastRunAtMs, lastExitCode);
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        if (!slug.isEmpty()) o.put("slug", slug);
        o.put("label", label);
        o.put("command", command);
        if (timeoutSeconds != null) o.put("timeoutSeconds", timeoutSeconds.intValue());
        o.put("createdAtMs", createdAtMs);
        if (lastRunAtMs != null) o.put("lastRunAtMs", lastRunAtMs.longValue());
        else o.put("lastRunAtMs", JSONObject.NULL);
        if (lastExitCode != null) o.put("lastExitCode", lastExitCode.intValue());
        else o.put("lastExitCode", JSONObject.NULL);
        return o;
    }

    static SavedCommand fromJson(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("id", "").trim();
        if (id.isEmpty()) return null;
        Long lastRun = null;
        if (o.has("lastRunAtMs") && !o.isNull("lastRunAtMs")) {
            lastRun = o.optLong("lastRunAtMs", 0L);
        }
        Integer lastExit = null;
        if (o.has("lastExitCode") && !o.isNull("lastExitCode")) {
            lastExit = o.optInt("lastExitCode", 0);
        }
        Integer timeout = null;
        if (o.has("timeoutSeconds") && !o.isNull("timeoutSeconds")) {
            timeout = o.optInt("timeoutSeconds", 0);
        }
        return new SavedCommand(
                id,
                o.optString("slug", "").trim(),
                o.optString("label", ""),
                o.optString("command", ""),
                timeout,
                o.optLong("createdAtMs", System.currentTimeMillis()),
                lastRun,
                lastExit);
    }
}
