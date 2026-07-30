package com.pegasuscorp.orbe.chat;

import org.json.JSONObject;

/** Appel d'outil structuré renvoyé par l'API Groq (OpenAI-compatible). */
public final class NativeToolCall {

    public final String id;
    public final String name;
    public final JSONObject arguments;

    public NativeToolCall(String id, String name, JSONObject arguments) {
        this.id = id != null ? id : "";
        this.name = name != null ? name : "";
        this.arguments = arguments != null ? arguments : new JSONObject();
    }
}
