package com.pegasuscorp.orbe.tools;

/** Catégorie d'outil pour filtrer la section prompt (ContextIntent → ToolRegistry). */
public enum ToolTag {
    NOTEPAD,
    MEMORY,
    DEVICE,
    WEATHER,
    SEARCH,
    NEWS,
    SPOTIFY,
    YOUTUBE,
    NOTIFICATIONS,
    ALARM,
    TIMER,
    CONNECTIVITY,
    FLASHLIGHT,
    NAVIGATION,
    CALL,
    SMS,
    EMAIL,
    SHARE,
    VOLUME,
    SETTINGS,
    CLIPBOARD,
    CONTACTS,
    FILES,
    NASA,
    WIKIPEDIA,
    WIKIDATA,
    WEB_SEARCH,
    CALENDAR,
    AGENDA,
    CALCULATOR,
    OPEN_APP,
    OPEN_INTERFACE,
    CREATE_FILE,
    NAMED_CONTEXT,
    DIAG,
    BRIEF,
    /** ui_action / ui_loop / ui_explain / ui_search / screen_capture / copilot_action — hors daily general. */
    UI,
    /** Companion F1 — hors SEARCH générique. */
    F1,
    /** Rythmes de vie — hors BRIEF générique. */
    LIFE_PATTERN,
    /** Fiches projet structurées — hors BRIEF générique. */
    PROJECT_OBJECT,
    ORION_MANAGER,
    ORION_CODE,
    GIT_COMMIT,
    COMPOSITE
}
