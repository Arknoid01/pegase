package com.pegasuscorp.orbe.tools.life;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.objects.ProjectObjectStore;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Fiches projet locales (hors Orion) — add / list / remove.
 */
public final class ProjectObjectTool implements Tool {

    @Override
    public String id() {
        return "project_object";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.BRIEF;
    }

    @Override
    public String description() {
        return "project_object(action:\"add\"|\"list\"|\"remove\", label?:str, notes?:str, "
                + "status?:str, id?:str) — Fiches projet locales. "
                + "add : « note le projet cuisine, statut en cours ». "
                + "list / remove.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        try {
            String action = params != null
                    ? params.optString("action", "list").trim().toLowerCase(Locale.ROOT)
                    : "list";
            ProjectObjectStore store = ProjectObjectStore.getInstance(ctx);
            if ("add".equals(action) || "ajoute".equals(action) || "note".equals(action)) {
                String label = params != null ? params.optString("label", "").trim() : "";
                String notes = params != null ? params.optString("notes", "") : "";
                String status = params != null ? params.optString("status", "actif") : "actif";
                if (TextUtils.isEmpty(label)) {
                    cb.onSuccess(ToolResult.text(
                            "Il me faut un nom de projet, ex. « note le projet cuisine »."));
                    return;
                }
                String id = store.upsertCustom(null, label, notes, status);
                if (id == null) {
                    cb.onError("Impossible d'enregistrer la fiche.");
                    return;
                }
                cb.onSuccess(ToolResult.text(
                        "Fiche « " + label + " » enregistrée (statut " + status + ")."));
                return;
            }
            if ("remove".equals(action) || "supprime".equals(action)) {
                String id = params != null ? params.optString("id", "").trim() : "";
                String label = params != null ? params.optString("label", "").trim() : "";
                if (TextUtils.isEmpty(id) && !TextUtils.isEmpty(label)) {
                    for (JSONObject o : store.listCustom()) {
                        if (label.equalsIgnoreCase(o.optString("label", ""))) {
                            id = o.optString("id", "");
                            break;
                        }
                    }
                }
                if (TextUtils.isEmpty(id) || !store.remove(id)) {
                    cb.onSuccess(ToolResult.text("Fiche introuvable."));
                    return;
                }
                cb.onSuccess(ToolResult.text("Fiche supprimée."));
                return;
            }
            List<JSONObject> all = store.listCustom();
            if (all.isEmpty()) {
                cb.onSuccess(ToolResult.text(
                        "Aucune fiche custom. Dis par exemple "
                                + "« note le projet cuisine, statut en cours »."));
                return;
            }
            StringBuilder sb = new StringBuilder("Tes fiches projet :\n");
            for (JSONObject o : all) {
                sb.append("- ").append(o.optString("label", "?"))
                        .append(" (").append(o.optString("status", "—")).append(")");
                String notes = o.optString("notes", "");
                if (!TextUtils.isEmpty(notes)) sb.append(" — ").append(notes);
                sb.append('\n');
            }
            cb.onSuccess(ToolResult.text(sb.toString().trim()));
        } catch (Exception e) {
            cb.onError("Fiche projet : " + e.getMessage());
        }
    }
}
