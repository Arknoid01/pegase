package com.pegasuscorp.orbe.diag;

/**
 * Catégories d'anomalies / hésitations pour les events de trace enrichis
 * et la synthèse {@link DiagSynthesizer}.
 */
public enum DiagCategory {
    /** Intention d'action détectée mais outil non parti (ou format douteux). */
    HESITATION,
    /** Outil appelé mais échoué (ou validation HTTP outil). */
    FAILURE,
    /** Prose « c'est fait » sans exécution — filtre anti-fantôme. */
    PHANTOM_BLOCKED,
    /** Boucle de répétition / même action en échec. */
    REPEATED_ACTION,
    /** Latence LLM &gt; 5 s. */
    SLOW_RESPONSE,
    /** Repli local / modèle de secours. */
    FALLBACK_USED,
    /** Copilote : cible UI introuvable (matcher). */
    COPILOT_MATCHER,
    /** Copilote : app hors whitelist. */
    COPILOT_WHITELIST,
    /** Copilote : confirmation Oui/Non (ask / cancel / stale). */
    COPILOT_CONFIRM,
    /** Copilote : service d'accessibilité indisponible ou coupé. */
    COPILOT_A11Y
}
