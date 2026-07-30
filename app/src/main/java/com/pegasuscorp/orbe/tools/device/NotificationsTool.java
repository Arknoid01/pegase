package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;

import com.pegasuscorp.orbe.PegaseNotificationListenerService;
import com.pegasuscorp.orbe.notifications.NotificationAccess;
import com.pegasuscorp.orbe.notifications.NotificationItem;

import org.json.JSONObject;

import java.util.List;

/**
 * Liste, ouvre et efface les notifications Android.
 */
public final class NotificationsTool implements Tool {

    @Override
    public String id() {
        return "notifications";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.NOTIFICATIONS;
    }

    @Override
    public String description() {
        return "notifications(action:list|open|dismiss|dismiss_all, index?:int, "
                + "app?:str, query?:str) — Notifications Android. "
                + "action=list pour lire, dismiss pour effacer une (index requis), "
                + "dismiss_all pour tout effacer, open pour ouvrir l'app source (index requis). "
                + "Utilise pour « mes notifs », « qu'est-ce que j'ai reçu », "
                + "« efface les notifications ».";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        if (!NotificationAccess.isEnabled(ctx)) {
            NotificationAccess.openSettings(ctx);
            cb.onSuccessAndExit(ToolResult.text(
                    "Autorise l'accès aux notifications pour Orbe dans les réglages, "
                            + "puis redemande-moi."));
            return;
        }

        PegaseNotificationListenerService svc = NotificationAccess.awaitService(ctx, 5);
        if (svc == null) {
            cb.onError("Je n'arrive pas à me connecter aux notifications. "
                    + "Vérifie l'accès dans les réglages ou redémarre Orbe.");
            return;
        }

        String action = params.optString("action", "list").trim().toLowerCase();
        switch (action) {
            case "list":
                handleList(ctx, svc, cb);
                break;
            case "open":
                handleOpen(svc, params, cb);
                break;
            case "dismiss":
            case "clear":
                handleDismiss(svc, params, cb);
                break;
            case "dismiss_all":
            case "clear_all":
                int cleared = svc.dismissAllClearable();
                if (cleared > 0) {
                    cb.onSuccess(ToolResult.text("J'ai effacé " + cleared + " notification"
                            + (cleared > 1 ? "s" : "") + "."));
                } else {
                    cb.onSuccess(ToolResult.text("Il n'y avait rien à effacer."));
                }
                break;
            default:
                handleList(ctx, svc, cb);
                break;
        }
    }

    private void handleList(Context ctx, PegaseNotificationListenerService svc, ToolCallback cb) {
        List<NotificationItem> items = svc.snapshot(12);
        cb.onSuccess(ToolResult.text(PegaseNotificationListenerService.formatForSpeech(ctx, items)));
    }

    private void handleOpen(PegaseNotificationListenerService svc, JSONObject params,
                            ToolCallback cb) {
        int index = params.optInt("index", 0);
        String app = params.optString("app", "").trim();
        String query = params.optString("query", "").trim();
        if (query.isEmpty() && !app.isEmpty()) query = app;

        boolean ok;
        if (index > 0) {
            ok = svc.openByIndex(index);
        } else if (!query.isEmpty()) {
            ok = svc.openByQuery(query);
        } else {
            cb.onError("Dis-moi laquelle ouvrir, par numéro ou par application.");
            return;
        }

        if (ok) {
            cb.onSuccessAndExit(ToolResult.text("J'ouvre la notification."));
        } else {
            cb.onError("Je n'ai pas pu ouvrir cette notification.");
        }
    }

    private void handleDismiss(PegaseNotificationListenerService svc, JSONObject params,
                               ToolCallback cb) {
        int index = params.optInt("index", 0);
        String app = params.optString("app", "").trim();
        String query = params.optString("query", "").trim();
        if (query.isEmpty() && !app.isEmpty()) query = app;

        if (index > 0) {
            NotificationItem item = svc.findByIndex(index);
            if (item == null) {
                cb.onError("Je ne vois pas la notification numéro " + index + ".");
                return;
            }
            if (!item.clearable) {
                cb.onError("Cette notification ne peut pas être effacée "
                        + "(service en cours ou notification persistante).");
                return;
            }
            if (svc.dismissByIndex(index)) {
                cb.onSuccess(ToolResult.text("C'est effacé."));
            } else {
                cb.onError("Je n'ai pas pu effacer cette notification.");
            }
            return;
        }

        if (!query.isEmpty()) {
            if (svc.dismissByQuery(query)) {
                cb.onSuccess(ToolResult.text("C'est effacé."));
            } else {
                cb.onError("Je n'ai pas trouvé de notification effaçable pour « " + query + " ».");
            }
            return;
        }

        cb.onError("Précise quelle notification effacer, par numéro ou par application.");
    }
}
