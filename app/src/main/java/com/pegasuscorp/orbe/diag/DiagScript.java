package com.pegasuscorp.orbe.diag;

/** Une requête prédéfinie du mini-banc de test diagnostic. */
public final class DiagScript {

    public final String id;
    public final String label;
    public final String query;
    public final boolean requiresTavily;

    public DiagScript(String id, String label, String query) {
        this(id, label, query, false);
    }

    public DiagScript(String id, String label, String query, boolean requiresTavily) {
        this.id = id;
        this.label = label;
        this.query = query;
        this.requiresTavily = requiresTavily;
    }
}
