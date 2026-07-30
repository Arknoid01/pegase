package com.pegasuscorp.orbe.tools;

import android.content.Context;

import org.json.JSONObject;

/**
 * Contrat d'un outil que Pégase peut utiliser à la demande du LLM.
 */
public interface Tool {

    /** Identifiant unique (snake_case) utilisé dans le JSON du LLM. */
    String id();

    /** Ligne de description envoyée au LLM — format: id(params) — description */
    String description();

    /** Catégorie pour le filtrage du prompt système. */
    ToolTag tag();

    /**
     * Exécute l'outil.
     * @param ctx     contexte Android (Application)
     * @param params  objet JSON extrait de la réponse LLM
     * @param cb      callback de résultat
     */
    void execute(Context ctx, JSONObject params, ToolCallback cb);
}
