package com.pegasuscorp.orbe.tools.life;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.life.LifePatternStore;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Rythmes de vie locaux — ajouter / lister (pas de réseau).
 */
public final class LifePatternTool implements Tool {

    @Override
    public String id() {
        return "life_pattern";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.BRIEF;
    }

    @Override
    public String description() {
        return "life_pattern(action:\"add\"|\"list\", utterance?:str, label?:str) — "
                + "Rythmes de vie déclarés (local). "
                + "add : « ajoute un rythme ménage de 18h30 à 19h45 ». "
                + "list : liste les rythmes.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        try {
            String action = params != null
                    ? params.optString("action", "list").trim().toLowerCase(Locale.ROOT)
                    : "list";
            if ("add".equals(action) || "ajoute".equals(action)) {
                String utterance = params != null ? params.optString("utterance", "") : "";
                if (TextUtils.isEmpty(utterance)) {
                    utterance = params != null ? params.optString("label", "") : "";
                }
                LifePatternStore.LifePattern p =
                        LifePatternStore.getInstance(ctx).addFromUtterance(utterance);
                if (p == null) {
                    cb.onSuccess(ToolResult.text(
                            "Il me faut une plage horaire, par exemple "
                                    + "« ménage de 18h30 à 19h45 »."));
                    return;
                }
                cb.onSuccess(ToolResult.text(
                        "C'est noté : « " + p.label + " » " + p.timeLabel()
                                + ". Je m'en souviendrai en local."));
                return;
            }
            List<LifePatternStore.LifePattern> all =
                    LifePatternStore.getInstance(ctx).listAll();
            if (all.isEmpty()) {
                cb.onSuccess(ToolResult.text(
                        "Aucun rythme pour l'instant. Dis par exemple "
                                + "« ajoute un rythme ménage de 18h30 à 19h45 »."));
                return;
            }
            StringBuilder sb = new StringBuilder("Tes rythmes :\n");
            for (LifePatternStore.LifePattern p : all) {
                sb.append("- ").append(p.active ? "" : "(off) ")
                        .append(p.label).append(" ").append(p.timeLabel()).append('\n');
            }
            cb.onSuccess(ToolResult.text(sb.toString().trim()));
        } catch (Exception e) {
            cb.onError("Rythme : " + e.getMessage());
        }
    }
}
