package com.pegasuscorp.orbe.tools.orion;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;

import com.pegasuscorp.orbe.orion.OrionManagerActions;
import com.pegasuscorp.orbe.orion.OrionStateStore;
import com.pegasuscorp.orbe.orion.OrionStatus;

import org.json.JSONObject;

/**
 * Gestion du pod RunPod Orion.
 * <p>
 * {@code orion_manager(action:start|stop|status|list_pods)} —
 * start exige toujours une confirmation avec coût affiché ;
 * volume {@code immediate_amber_shark} toujours attaché.
 */
public final class OrionManagerTool implements Tool {

    @Override
    public String id() {
        return "orion_manager";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.ORION_MANAGER;
    }

    @Override
    public String description() {
        return "orion_manager(action:\"start\"|\"start_comfy\"|\"stop\"|\"status\"|\"list_pods\") — "
                + "Gère le pod RunPod (Orion/Ollama ou ComfyUI). "
                + "TOUJOURS spécifier action. Sans action → status uniquement. "
                + "Orion : action=\"start\". Comfy : action=\"start_comfy\" (GPU ≥24 Go). "
                + "Un seul pod à la fois. stop pour éteindre. "
                + "confirm?:bool, gpu_id?:str, index?:int. "
                + "« lance Orion », « lance Comfy », « éteins Orion ».";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String action = readAction(params);
        // params={} ou action manquante → status immédiat + hint (pas de boucle start)
        if (action.isEmpty()) {
            OrionStateStore store = OrionStateStore.get();
            if (ctx != null) store.attach(ctx);
            OrionStatus st = store.getStatus();
            cb.onSuccess(ToolResult.text(
                    store.getPodMode().label() + " est " + st.label() + ". "
                            + "Pour Orion : 'lance Orion'. Pour Comfy : 'lance Comfy'. "
                            + "Pour arrêter : 'éteins Orion'."));
            return;
        }
        switch (action) {
            case "start":
            case "launch":
            case "boot":
                OrionManagerActions.start(ctx, params, cb);
                break;
            case "start_comfy":
            case "comfy":
            case "launch_comfy":
                OrionManagerActions.startComfy(ctx, params, cb);
                break;
            case "stop":
            case "shutdown":
            case "off":
                OrionManagerActions.stop(ctx, cb);
                break;
            case "list_pods":
            case "list":
            case "gpus":
                OrionManagerActions.listPods(ctx, cb);
                break;
            case "status":
            case "cost":
            case "state":
            default:
                OrionManagerActions.status(ctx, cb);
                break;
        }
    }

    /** Action normalisée — vide si absente ou blank (≠ défaut status). */
    public static String readAction(JSONObject params) {
        if (params == null || !params.has("action")) return "";
        Object raw = params.opt("action");
        if (raw == null || raw == JSONObject.NULL) return "";
        return String.valueOf(raw).trim().toLowerCase();
    }
}
