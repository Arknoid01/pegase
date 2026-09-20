package com.pegasuscorp.orbe.sync;

import org.json.JSONObject;

/** Point de reprise dans le journal d'un pair. */
public final class SyncCursor {

    public final long lastSeq;
    public final String lastOpId;
    public final long lastTsMs;

    public SyncCursor(long lastSeq, String lastOpId, long lastTsMs) {
        this.lastSeq = lastSeq;
        this.lastOpId = lastOpId != null ? lastOpId : "";
        this.lastTsMs = lastTsMs;
    }

    public static SyncCursor empty() {
        return new SyncCursor(0, "", 0);
    }

    JSONObject toJson() throws Exception {
        return new JSONObject()
                .put("lastSeq", lastSeq)
                .put("lastOpId", lastOpId)
                .put("lastTsMs", lastTsMs);
    }

    static SyncCursor fromJson(JSONObject o) {
        if (o == null) return empty();
        return new SyncCursor(
                o.optLong("lastSeq", 0),
                o.optString("lastOpId", ""),
                o.optLong("lastTsMs", 0));
    }
}
