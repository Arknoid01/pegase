package com.pegasuscorp.orbe.orion;

/** Taille estimée d'une mission Orion. */
public enum TaskComplexity {
    /** 1 fichier, 1–2 items, mot-clé trouvé. */
    SIMPLE,
    /** 1–2 fichiers, 3–5 items. */
    MEDIUM,
    /** 3+ fichiers ou grosse mission sans mot-clé. */
    LARGE,
    /** Refactoring majeur (« refais tout », « restructure »…). */
    MASSIVE
}
