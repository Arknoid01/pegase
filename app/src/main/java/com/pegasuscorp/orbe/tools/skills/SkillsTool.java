package com.pegasuscorp.orbe.tools.skills;

import android.content.Context;

import com.pegasuscorp.orbe.skills.SkillStore;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.ToolTag;

import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

/** Skills : procédures spécialisées chargées à la demande (SKILL.md + ressources). */
public final class SkillsTool implements Tool {

    @Override
    public String id() {
        return "skills";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.SKILLS;
    }

    @Override
    public String description() {
        return "skills(action:list|read|read_resource, id?:str, path?:str) — "
                + "Procédures spécialisées. list = skills installés ; read = lire les consignes "
                + "d'un skill (id) AVANT de l'appliquer ; read_resource = fichier annexe d'un "
                + "skill (path relatif, listé par read). Appelle read dès qu'une demande "
                + "correspond à un skill de l'index.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        SkillStore store = new SkillStore(ctx);
        String action = params.optString("action", "list").trim().toLowerCase();
        try {
            switch (action) {
                case "list":
                    cb.onSuccess(ToolResult.text(formatList(store)));
                    return;
                case "read": {
                    SkillStore.Skill skill = require(store, params, cb);
                    if (skill == null) return;
                    StringBuilder sb = new StringBuilder("Skill « ").append(skill.name)
                            .append(" » :\n").append(store.readBody(skill));
                    List<String> res = store.listResources(skill);
                    if (!res.isEmpty()) {
                        sb.append("\n\nRessources (skills read_resource, path) : ")
                                .append(String.join(", ", res));
                    }
                    cb.onSuccess(ToolResult.text(sb.toString()));
                    return;
                }
                case "read_resource": {
                    SkillStore.Skill skill = require(store, params, cb);
                    if (skill == null) return;
                    cb.onSuccess(ToolResult.text(
                            store.readResource(skill, params.optString("path", ""))));
                    return;
                }
                default:
                    cb.onError("Action inconnue : " + action + " (list, read, read_resource).");
            }
        } catch (IOException e) {
            cb.onError(e.getMessage() != null ? e.getMessage() : "Lecture du skill impossible.");
        }
    }

    private static SkillStore.Skill require(SkillStore store, JSONObject params,
            ToolCallback cb) {
        String id = params.optString("id", "").trim();
        if (id.isEmpty()) {
            cb.onError("Précise l'id du skill (voir skills list).");
            return null;
        }
        SkillStore.Skill skill = store.find(id);
        if (skill == null) {
            cb.onError("Skill inconnu : " + id + ". " + formatList(store));
            return null;
        }
        return skill;
    }

    private static String formatList(SkillStore store) {
        List<SkillStore.Skill> skills = store.list();
        if (skills.isEmpty()) return "Aucun skill installé.";
        StringBuilder sb = new StringBuilder("Skills installés :");
        for (SkillStore.Skill s : skills) {
            sb.append("\n- ").append(s.id).append(" : ").append(s.description);
        }
        return sb.toString();
    }
}
