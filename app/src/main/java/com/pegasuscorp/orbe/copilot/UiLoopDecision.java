package com.pegasuscorp.orbe.copilot;

import android.text.TextUtils;

import org.json.JSONObject;

/**
 * Décision d'un tour {@link UiLoopRunner} — une action UI ou fin de boucle.
 */
public final class UiLoopDecision {

    public enum Kind {
        ACTION,
        FINISH_OK,
        FINISH_FAIL,
        FINISH_NEED_CONFIRM,
        INVALID
    }

    public final Kind kind;
    /** click|type|scroll|back|open */
    public final String action;
    public final JSONObject params;
    public final String reason;

    private UiLoopDecision(Kind kind, String action, JSONObject params, String reason) {
        this.kind = kind;
        this.action = action != null ? action : "";
        this.params = params != null ? params : new JSONObject();
        this.reason = reason != null ? reason : "";
    }

    public static UiLoopDecision invalid(String reason) {
        return new UiLoopDecision(Kind.INVALID, "", null, reason);
    }

    public static UiLoopDecision parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return invalid("réponse vide");
        }
        JSONObject o = extractJson(raw);
        if (o == null) return invalid("JSON introuvable");

        // finish_task en racine
        String finish = o.optString("finish_task", "").trim();
        if (!finish.isEmpty()) {
            return parseFinish(finish,
                    o.optString("reason", o.optString("message", "")));
        }
        String tool = o.optString("tool", "").trim().toLowerCase();
        if ("finish_task".equals(tool) || "ui_finish".equals(tool)) {
            JSONObject p = o.optJSONObject("params");
            String status = p != null
                    ? p.optString("status", p.optString("result", "ok"))
                    : o.optString("status", "ok");
            String reason = p != null
                    ? p.optString("reason", p.optString("message", ""))
                    : o.optString("reason", "");
            return parseFinish(status, reason);
        }

        String action = o.optString("action", "").trim().toLowerCase();
        if (action.isEmpty() && "ui_action".equals(tool)) {
            JSONObject p = o.optJSONObject("params");
            if (p != null) {
                action = p.optString("action", "").trim().toLowerCase();
                o = p;
            }
        }
        if (action.isEmpty()) return invalid("pas d'action ni finish_task");

        switch (action) {
            case "click":
            case "tap":
            case "type":
            case "set_text":
            case "scroll":
            case "back":
            case "retour":
            case "go_back":
            case "open":
            case "launch":
            case "open_app":
                return new UiLoopDecision(Kind.ACTION, normalizeAction(action), o,
                        o.optString("reason", ""));
            case "finish":
            case "done":
            case "finish_task":
                return parseFinish(o.optString("status", "ok"),
                        o.optString("reason", ""));
            default:
                return invalid("action inconnue : " + action);
        }
    }

    private static String normalizeAction(String action) {
        switch (action) {
            case "tap":
                return "click";
            case "set_text":
                return "type";
            case "retour":
            case "go_back":
                return "back";
            case "launch":
            case "open_app":
                return "open";
            default:
                return action;
        }
    }

    private static UiLoopDecision parseFinish(String statusRaw, String reason) {
        String s = statusRaw != null ? statusRaw.trim().toLowerCase() : "";
        if (s.equals("ok") || s.equals("success") || s.equals("done") || s.equals("true")) {
            return new UiLoopDecision(Kind.FINISH_OK, "", null, reason);
        }
        if (s.equals("need_confirm") || s.equals("confirm") || s.equals("ask")) {
            return new UiLoopDecision(Kind.FINISH_NEED_CONFIRM, "", null, reason);
        }
        if (s.equals("fail") || s.equals("failed") || s.equals("error") || s.equals("false")) {
            return new UiLoopDecision(Kind.FINISH_FAIL, "", null, reason);
        }
        // finish_task sans status clair : si reason non vide → fail, sinon ok
        if (!TextUtils.isEmpty(reason) && (s.isEmpty() || s.equals("finish_task"))) {
            return new UiLoopDecision(Kind.FINISH_FAIL, "", null, reason);
        }
        if (s.isEmpty()) {
            return new UiLoopDecision(Kind.FINISH_OK, "", null, reason);
        }
        return new UiLoopDecision(Kind.FINISH_FAIL, "", null, reason.isEmpty() ? s : reason);
    }

    static JSONObject extractJson(String text) {
        if (text == null) return null;
        String json = text.trim();
        // fence markdown
        int fence = json.indexOf("```");
        if (fence >= 0) {
            int start = json.indexOf('{', fence);
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
        } else {
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            json = json.substring(start, end + 1);
        }
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            return null;
        }
    }
}
