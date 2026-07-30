package com.pegasuscorp.orbe.orion;

/**
 * Ligne GPU pour l'UI Orion — l'état {@link #isAllowed} vit dans le modèle,
 * jamais dans la vue recyclée.
 */
public final class GpuOption {

    public final GpuOffer offer;
    /** Autorisé pour démarrage Orion — source de vérité pour la checkbox. */
    public boolean isAllowed;

    public GpuOption(GpuOffer offer, boolean isAllowed) {
        this.offer = offer;
        this.isAllowed = isAllowed;
    }

    public String id() {
        return offer != null ? offer.id : "";
    }

    public String label() {
        if (offer == null) return "";
        return offer.shortLabel() + (offer.available ? "" : " (indispo)");
    }
}
