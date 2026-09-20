package com.pegasuscorp.orbe.sync;

import org.json.JSONObject;

/**
 * Entrée append-only du journal de synchronisation.
 */
public final class SyncOp {

    public static final String STORE_NOTEPAD = "notepad";
    public static final String STORE_MEMORY = "memory";
    public static final String STORE_CONVERSATION = "conversation";
    public static final String OP_UPSERT = "upsert";
    public static final String OP_DELETE = "delete";

    public final String opId;
    public final long seq;
    public final long tsMs;
    public final String deviceId;
    public final String store;
    public final String op;
    public final String recordId;
    public final JSONObject payload;

    SyncOp(String opId, long seq, long tsMs, String deviceId, String store,
            String op, String recordId, JSONObject payload) {
        this.opId = opId != null ? opId : "";
        this.seq = seq;
        this.tsMs = tsMs;
        this.deviceId = deviceId != null ? deviceId : "";
        this.store = store != null ? store : "";
        this.op = op != null ? op : "";
        this.recordId = recordId != null ? recordId : "";
        this.payload = payload;
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("opId", opId);
        o.put("seq", seq);
        o.put("tsMs", tsMs);
        o.put("deviceId", deviceId);
        o.put("store", store);
        o.put("op", op);
        o.put("recordId", recordId);
        if (payload != null) o.put("payload", payload);
        else o.put("payload", JSONObject.NULL);
        return o;
    }

    static SyncOp fromJson(JSONObject o) {
        if (o == null) return null;
        String opId = o.optString("opId", "").trim();
        if (opId.isEmpty()) return null;
        JSONObject payload = o.optJSONObject("payload");
        return new SyncOp(
                opId,
                o.optLong("seq", 0),
                o.optLong("tsMs", 0),
                o.optString("deviceId", ""),
                o.optString("store", ""),
                o.optString("op", ""),
                o.optString("recordId", ""),
                payload);
    }
}
