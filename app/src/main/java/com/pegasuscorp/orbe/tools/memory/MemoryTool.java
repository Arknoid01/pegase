package com.pegasuscorp.orbe.tools.memory;

import com.pegasuscorp.orbe.tools.knowledge.BriefTool;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;

import com.pegasuscorp.orbe.memory.MemoryEntry;
import com.pegasuscorp.orbe.memory.MemoryRepository;
import com.pegasuscorp.orbe.memory.EphemeralMemoryFilter;

import org.json.JSONObject;

import java.util.List;

/** Souvenirs permanents de Pégase — add / list / forget. */
public final class MemoryTool implements Tool {

    @Override
    public String id() {
        return "memory";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.MEMORY;
    }

    @Override
    public String description() {
        return "memory(action:\"add\"|\"list\"|\"forget\", text?:str) — "
                + "Mémoire permanente : préférences, habitudes, infos personnelles durables. "
                + "Utilise pour « souviens-toi que j'aime... », « retiens que je... », "
                + "« n'oublie pas que... ». "
                + "NE PAS utiliser pour des tâches ou listes — utilise notepad pour ça. "
                + "NE JAMAIS mémoriser un résultat d'outil / action UI "
                + "(clic, scroll, saisie, ouverture d'app).";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        MemoryRepository repo = MemoryRepository.getInstance(ctx);
        String action = params.optString("action", "list").toLowerCase();

        switch (action) {
            case "add":
            case "remember": {
                String text = params.optString("text", "").trim();
                if (text.isEmpty()) {
                    cb.onError("Précise ce que je dois retenir.");
                    return;
                }
                if (EphemeralMemoryFilter.isNoise(text)) {
                    cb.onSuccess(ToolResult.text(
                            "Ça, c'est juste une action du moment — je ne le garde pas en mémoire."));
                    return;
                }
                repo.addPermanentMemory(new MemoryEntry(
                        "preference", text, 0.85,
                        new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                                .format(new java.util.Date()),
                        BriefTool.isOnFallbackBackend(ctx)
                                ? MemoryEntry.SOURCE_FALLBACK
                                : MemoryEntry.SOURCE_USER));
                cb.onSuccess(ToolResult.text("Je retiens : " + text));
                break;
            }
            case "forget":
            case "remove": {
                String text = params.optString("text", "").trim();
                if (text.isEmpty()) {
                    cb.onError("Précise quel souvenir effacer.");
                    return;
                }
                int removed = repo.removePermanentContaining(text);
                if (removed > 0) {
                    cb.onSuccess(ToolResult.text("J'ai effacé " + removed + " souvenir"
                            + (removed > 1 ? "s" : "") + "."));
                } else {
                    cb.onError("Je n'ai rien trouvé correspondant à « " + text + " ».");
                }
                break;
            }
            case "list":
            default: {
                List<MemoryEntry> memories = repo.getTopPermanentMemories(8);
                if (memories.isEmpty()) {
                    cb.onSuccess(ToolResult.text("Je n'ai pas encore de souvenirs enregistrés."));
                    return;
                }
                StringBuilder sb = new StringBuilder("Voici ce que je retiens : ");
                for (int i = 0; i < memories.size(); i++) {
                    if (i > 0) sb.append(i == memories.size() - 1 ? " et " : ", ");
                    String c = memories.get(i).content;
                    if (c.length() > 60) c = c.substring(0, 57) + "…";
                    sb.append(c);
                }
                sb.append('.');
                cb.onSuccess(ToolResult.text(sb.toString()));
            }
        }
    }
}
