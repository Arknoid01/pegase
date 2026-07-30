package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;



import android.content.Context;

import android.os.Handler;

import android.os.Looper;



import com.pegasuscorp.orbe.chat.ApiKeyStore;



import org.json.JSONObject;



import java.util.concurrent.ExecutorService;

import java.util.concurrent.Executors;



/**

 * Recherche web via Tavily — les extraits sont synthétisés par le LLM agentique final.

 */

public final class TavilyTool implements Tool {



    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());



    @Override public String id() { return "search"; }



    @Override public ToolTag tag() { return ToolTag.SEARCH; }



    @Override

    public String description() {

        return "search(query:str, question?:str) — Recherche web Tavily (actualité). "
                + "À utiliser SEULEMENT pour info récente, prix du jour, score, horaire, "
                + "« aujourd'hui / ce soir / récemment ». "
                + "Pour une définition ou un fait établi (« c'est quoi », « qui a inventé ») : "
                + "préférer wikipedia ou wikidata (gratuit, plus fiable).";
    }



    @Override

    public void execute(Context ctx, JSONObject params, ToolCallback cb) {

        if (!ApiKeyStore.hasTavilyKey(ctx)) {

            cb.onError("Clé Tavily manquante — saisis-la dans les réglages pour la recherche web.");

            return;

        }



        String query = params.optString("query", "").trim();

        if (query.isEmpty()) {

            cb.onError("Précise ce que tu veux rechercher.");

            return;

        }

        String question = params.optString("question", "").trim();

        if (question.isEmpty()) question = query;



        final String finalQuestion = question;

        io.execute(() -> {

            try {

                TavilySearchService.Bundle bundle = TavilySearchService.search(ctx, query);

                if (!bundle.hasContent()) {

                    main.post(() -> cb.onSuccess(ToolResult.text(

                            "Je n'ai pas trouvé d'information sur « " + query + " ».")));

                    return;

                }

                String display = bundle.fallbackSpeech();

                String llmContext = bundle.toLlmContext(finalQuestion);

                main.post(() -> cb.onSuccess(ToolResult.text(display, llmContext)));

            } catch (Exception e) {

                main.post(() -> cb.onError("Recherche impossible : " + e.getMessage()));

            }

        });

    }

}

