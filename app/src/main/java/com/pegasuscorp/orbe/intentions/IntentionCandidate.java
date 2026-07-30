package com.pegasuscorp.orbe.intentions;

/**
 * Candidat produit par une {@link com.pegasuscorp.orbe.intentions.rules.IntentionRule}.
 */
public final class IntentionCandidate {

    public final String id;
    public final String title;
    public final String body;
    /** Style d'actions : battery | work | brief */
    public final String actionStyle;

    public IntentionCandidate(String id, String title, String body, String actionStyle) {
        this.id = id;
        this.title = title == null ? "Pégase" : title;
        this.body = body == null ? "" : body;
        this.actionStyle = actionStyle == null ? "work" : actionStyle;
    }
}
