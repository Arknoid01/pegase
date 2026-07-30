package com.pegasuscorp.orbe.orion;

/** Risque d'une mission Orion (fichiers touchés, ampleur). */
public enum TaskRisk {
    /** CSS, compteur, retouche cosmétique. */
    LOW,
    /** Nouvelle méthode ou fichier isolé. */
    MEDIUM,
    /** Plusieurs fichiers liés ou mission LARGE/MASSIVE. */
    HIGH,
    /** Fichiers cœur Pégase / Orbe. */
    CRITICAL
}
