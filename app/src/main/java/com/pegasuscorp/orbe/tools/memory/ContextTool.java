package com.pegasuscorp.orbe.tools.memory;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;

import com.pegasuscorp.orbe.bureau.BureauMarkdownBuilder;
import com.pegasuscorp.orbe.bureau.BureauProject;
import com.pegasuscorp.orbe.bureau.BureauProjectStore;
import com.pegasuscorp.orbe.contextstore.ContextSearchIndex;
import com.pegasuscorp.orbe.contextstore.ContextualFileStore;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Contextes nommés (.md) : charge / décharge / liste / recherche sémantique.
 */
public final class ContextTool implements Tool {

    @Override
    public String id() {
        return "named_context";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.NAMED_CONTEXT;
    }

    @Override
    public String description() {
        return "named_context(action:load|unload|list|status|search, name?:string, query?:string) — "
                + "Fichiers de contexte projet (orion, boucherie, fableris, pegase…). "
                + "load avec name (ex. Orion) ; unload ; list ; status ; "
                + "search avec query pour chercher sémantiquement dans TOUS les .md "
                + "(ex. « Tavily », « token cmd.php »). "
                + "NE PAS confondre avec memory (souvenirs) ni notepad (liste à faire).";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        ContextualFileStore store = ContextualFileStore.getInstance(ctx);
        String action = params.optString("action", "list").trim().toLowerCase();
        String name = params.optString("name", params.optString("keyword", "")).trim();
        String query = params.optString("query", params.optString("text", "")).trim();

        switch (action) {
            case "load":
            case "charge": {
                if (name.isEmpty()) {
                    cb.onError("Précise le nom du contexte (ex. Orion).");
                    return;
                }
                List<String> parts = new ArrayList<>();
                for (String p : name.split("[,+]|\\s+et\\s+")) {
                    String t = p.trim();
                    if (!t.isEmpty()) parts.add(t);
                }
                List<String> ok = new ArrayList<>(store.loadMultiple(parts));
                if (ok.isEmpty()) {
                    // Fallback : plan Bureau pas encore mirroiré en contexte
                    for (String part : parts) {
                        if (tryLoadBureauAsContext(ctx, store, part)) {
                            ok.add(part);
                        }
                    }
                }
                if (ok.isEmpty()) {
                    cb.onError("Contexte introuvable : " + name);
                    return;
                }
                cb.onSuccess(ToolResult.text(
                        "Contextes chargés : " + String.join(", ", ok) + ".",
                        store.buildPromptSection()));
                break;
            }
            case "unload":
            case "decharge":
            case "décharge": {
                String speech = name.isEmpty() ? store.unload("tout") : store.unload(name);
                cb.onSuccess(ToolResult.text(speech));
                break;
            }
            case "status":
            case "loaded":
                cb.onSuccess(ToolResult.text(store.formatLoadedForSpeech()));
                break;
            case "search":
            case "cherche": {
                if (query.isEmpty()) query = name;
                if (query.isEmpty()) {
                    cb.onError("Précise query (ex. Tavily).");
                    return;
                }
                ContextSearchIndex idx = ContextSearchIndex.getInstance(ctx);
                List<ContextSearchIndex.Hit> hits =
                        idx.search(query, 3, ContextSearchIndex.MIN_SCORE);
                if (hits.isEmpty()) {
                    idx.indexAllNow();
                    hits = idx.search(query, 3, ContextSearchIndex.MIN_SCORE);
                }
                String speech = idx.formatSearchForSpeech(hits, query);
                String llm = idx.formatSearchForLlm(hits);
                cb.onSuccess(ToolResult.text(speech, llm.isEmpty() ? speech : llm));
                break;
            }
            case "list":
            default:
                cb.onSuccess(ToolResult.text(store.formatListForSpeech()));
                break;
        }
    }

    /** Enregistre + charge un plan Bureau comme contexte nommé. */
    private static boolean tryLoadBureauAsContext(Context ctx, ContextualFileStore store,
            String name) {
        if (ctx == null || store == null || name == null || name.trim().isEmpty()) return false;
        String slug = name.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", "-");
        try {
            String md = BureauProjectStore.loadMarkdown(ctx, slug);
            if (md == null || md.trim().isEmpty()) {
                BureauProject p = BureauProjectStore.load(ctx, slug);
                if (p == null) {
                    // Essai sur le slug exact listé
                    for (String s : BureauProjectStore.listSlugs(ctx)) {
                        if (s != null && (s.equalsIgnoreCase(name.trim())
                                || s.contains(slug) || slug.contains(s))) {
                            p = BureauProjectStore.load(ctx, s);
                            slug = s;
                            break;
                        }
                    }
                }
                if (p != null) md = BureauMarkdownBuilder.render(p);
            }
            if (md == null || md.trim().isEmpty()) return false;
            store.save(slug, md);
            List<String> loaded = store.loadMultiple(java.util.Collections.singletonList(slug));
            return loaded != null && !loaded.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
