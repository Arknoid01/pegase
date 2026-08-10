package com.pegasuscorp.orbe.pcshell;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Actions ntfy standard pour les notifications « mises à jour Arch ».
 */
public final class PcShellNtfyActions {

    private PcShellNtfyActions() {}

    static String buildUpdateActionsJson(String topic, String title, String message) throws Exception {
        String yes = "pegase://pc-shell/run?autoRunCommand=mise-a-jour-arch";
        String no = "pegase://pc-shell/ntfy?action=dismiss";
        String later = "pegase://pc-shell/ntfy?action=snooze&hours=4"
                + "&topic=" + enc(topic)
                + "&title=" + enc(title)
                + "&message=" + enc(message != null ? message : "");

        JSONArray arr = new JSONArray();
        arr.put(action("yes", "Oui", yes));
        arr.put(action("no", "Non", no));
        arr.put(action("later", "Plus tard", later));
        return arr.toString();
    }

    private static JSONObject action(String id, String label, String url) throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("action", "view")
                .put("label", label)
                .put("url", url)
                .put("clear", true);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8);
    }
}
