package com.pegasuscorp.orbe.learning;

import org.json.JSONObject;

/**
 * Signal local observé — pas une vérité déclarée.
 */
public final class Observation {

    public static final String TYPE_INTENTION_FEEDBACK = "intention_feedback";
    public static final String TYPE_BT_ACL = "bt_acl";
    public static final String TYPE_WIFI_SSID = "wifi_ssid";

    public final String type;
    public final long atMs;
    public final JSONObject payload;

    public Observation(String type, long atMs, JSONObject payload) {
        this.type = type == null ? "" : type;
        this.atMs = atMs;
        this.payload = payload != null ? payload : new JSONObject();
    }

    public JSONObject toJson() throws Exception {
        return new JSONObject()
                .put("type", type)
                .put("atMs", atMs)
                .put("payload", payload);
    }

    public static Observation fromJson(JSONObject o) {
        if (o == null) return null;
        JSONObject payload = o.optJSONObject("payload");
        if (payload == null) payload = new JSONObject();
        return new Observation(o.optString("type", ""), o.optLong("atMs", 0L), payload);
    }
}
