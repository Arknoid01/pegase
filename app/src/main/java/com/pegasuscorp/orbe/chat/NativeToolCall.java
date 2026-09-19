package com.pegasuscorp.orbe.chat;

import org.json.JSONObject;

/** Appel d'outil structuré renvoyé par l'API Groq (OpenAI-compatible). */
public final class NativeToolCall {

    /** Arguments illisibles (JSON invalide). */
    public static final String REJECT_MALFORMED_ARGUMENTS = "INVALID_TOOL_ARGUMENTS";
    /** Réponse coupée par la limite de longueur : les arguments peuvent être incomplets. */
    public static final String REJECT_TRUNCATED = "TRUNCATED_TOOL_CALL";

    public final String id;
    public final String name;
    public final JSONObject arguments;
    /** Non nul : l'appel ne doit pas être exécuté ; le modèle recevra ce motif comme résultat. */
    public final String rejectionCode;
    public final String rejectionMessage;

    public NativeToolCall(String id, String name, JSONObject arguments) {
        this(id, name, arguments, null, null);
    }

    public NativeToolCall(String id, String name, JSONObject arguments,
            String rejectionCode, String rejectionMessage) {
        this.id = id != null ? id : "";
        this.name = name != null ? name : "";
        this.arguments = arguments != null ? arguments : new JSONObject();
        this.rejectionCode = rejectionCode;
        this.rejectionMessage = rejectionMessage;
    }

    public boolean isRejected() {
        return rejectionCode != null;
    }
}
