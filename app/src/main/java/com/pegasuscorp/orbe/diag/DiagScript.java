package com.pegasuscorp.orbe.diag;

import com.pegasuscorp.orbe.session.Channel;

/** Une requête prédéfinie du mini-banc de test diagnostic. */
public final class DiagScript {

    public final String id;
    public final String label;
    public final String query;
    public final boolean requiresTavily;
    /** Canal session pour ce pas (null = TEXT). */
    public final Channel channel;

    public DiagScript(String id, String label, String query) {
        this(id, label, query, false, null);
    }

    public DiagScript(String id, String label, String query, boolean requiresTavily) {
        this(id, label, query, requiresTavily, null);
    }

    public DiagScript(String id, String label, String query, boolean requiresTavily,
            Channel channel) {
        this.id = id;
        this.label = label;
        this.query = query;
        this.requiresTavily = requiresTavily;
        this.channel = channel;
    }
}
