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

    /** Réponse quand la demande ne contient presque que des données personnelles. */

    static final String REFUS_PRIVE = "Je ne lance pas cette recherche sur le web : une fois "

            + "tes infos personnelles retirées, il ne reste rien d'utile à chercher.";

    private final Handler main = new Handler(Looper.getMainLooper());



    @Override public String id() { return "search"; }



    @Override public ToolTag tag() { return ToolTag.SEARCH; }



    @Override

    public String description() {

        return "search(query:str, question?:str) — Recherche web (plusieurs sources). "
                + "TON OUTIL PRINCIPAL pour tout fait vérifiable : actualité, sport, résultats, "
                + "personnes, entreprises, bourse, prix, produits, sorties, horaires, lieux, "
                + "déclarations, « tu sais si… », « c'est vrai que… ». Dans le doute, cherche "
                + "plutôt que répondre de mémoire (tes connaissances datent). query courte et "
                + "précise (noms propres, année). Définition pure / concept : wikipedia possible.";
    }



    @Override

    public void execute(Context ctx, JSONObject params, ToolCallback cb) {

        String query = params.optString("query", "").trim();

        if (query.isEmpty()) {

            cb.onError("Précise ce que tu veux rechercher.");

            return;

        }

        String question = params.optString("question", "").trim();

        if (question.isEmpty()) question = query;

        final String finalQuestion = question;

        final boolean prive = com.pegasuscorp.orbe.search.MoteurPrive.pret(ctx);

        // Confidentialité (étape C) : AUCUN moteur ne reçoit e-mail, téléphone, adresse, IBAN,

        // ni les termes privés (nom du profil + liste des réglages) — Tavily compris.

        final java.util.List<String> termes =

                com.pegasuscorp.orbe.search.ReglagesRecherche.termesPrives(ctx);

        final String requeteTavily = com.pegasuscorp.orbe.search.Confidentialite.nettoyer(

                query, termes, new java.util.ArrayList<>());

        final String questionTavily = com.pegasuscorp.orbe.search.Confidentialite.nettoyer(

                finalQuestion, termes, new java.util.ArrayList<>());

        if (!prive && !ApiKeyStore.hasTavilyKey(ctx)) {

            cb.onError("Recherche web non configurée — ajoute une clé Brave (recherche privée) "

                    + "ou Tavily dans les réglages.");

            return;

        }

        io.execute(() -> {

            // 1) Moteur privé (Brave + lecture des pages sur le téléphone), décidé le 2026-11-23 :

            //    meilleur sur l'actu et les fausses prémisses, rien d'inventé. Question en

            //    langage naturel (« question ») : c'est avec elle que le moteur a été mesuré.

            com.pegasuscorp.orbe.search.Dossier dossier = null;

            if (prive) {

                try {

                    dossier = com.pegasuscorp.orbe.search.MoteurPrive.chercher(ctx, finalQuestion);

                } catch (Throwable e) {   // y compris les erreurs fatales : on bascule sur Tavily

                    android.util.Log.w("TavilyTool", "moteur privé : " + e);

                }

                if (dossier != null && !dossier.abstention && !dossier.passages.isEmpty()) {

                    final com.pegasuscorp.orbe.search.Dossier d = dossier;

                    main.post(() -> cb.onSuccess(ToolResult.text(d.reponse,

                            com.pegasuscorp.orbe.search.MoteurRecherche.contexteLlm(d)

                                    + "\n[moteur : recherche privée (" + d.sourceWeb + ")]")));

                    return;

                }

            }

            // Requête trop personnelle une fois nettoyée : on ne l'envoie NULLE PART

            if ((dossier != null && dossier.refus != null) || (dossier == null && requeteTavily == null)) {

                main.post(() -> cb.onSuccess(ToolResult.text(REFUS_PRIVE,

                        "[Recherche web non lancée : la demande ne contient presque que des "

                                + "informations personnelles. Réponds sans le web ou demande une "

                                + "formulation sans données perso.]")));

                return;

            }

            // 2) Tavily en secours (abstention, échec, ou moteur privé pas prêt)

            if (!ApiKeyStore.hasTavilyKey(ctx) || requeteTavily == null) {

                final com.pegasuscorp.orbe.search.Dossier d = dossier;

                main.post(() -> {

                    if (d != null) {

                        cb.onSuccess(ToolResult.text(d.reponse,

                                com.pegasuscorp.orbe.search.MoteurRecherche.contexteLlm(d)

                                        + "\n[moteur : recherche privée, sans réponse ; pas de Tavily]"));

                    } else {

                        cb.onError("Recherche impossible pour le moment.");

                    }

                });

                return;

            }

            try {

                TavilySearchService.Bundle bundle = TavilySearchService.search(ctx, requeteTavily);

                if (!bundle.hasContent()) {

                    main.post(() -> cb.onSuccess(ToolResult.text(

                            "Je n'ai pas trouvé d'information sur « " + query + " ».")));

                    return;

                }

                String display = bundle.fallbackSpeech();

                String llmContext = bundle.toLlmContext(questionTavily != null ? questionTavily : requeteTavily)

                        + (prive ? "\n[moteur : Tavily (secours du moteur privé)]" : "\n[moteur : Tavily]");

                main.post(() -> cb.onSuccess(ToolResult.text(display, llmContext)));

            } catch (Exception e) {

                main.post(() -> cb.onError("Recherche impossible : " + e.getMessage()));

            }

        });

    }

}

