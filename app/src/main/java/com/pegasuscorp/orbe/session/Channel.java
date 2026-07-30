package com.pegasuscorp.orbe.session;

/** Canal d'entrée — voix, texte, bureau, Orion (code), copilote overlay. */
public enum Channel {
    VOICE,
    TEXT,
    BUREAU,
    ORION,
    /** Orbe flottante + bulle messenger par-dessus les autres apps. */
    COPILOT
}
