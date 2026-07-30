package com.pegasuscorp.orbe.orion.search;

/**
 * Emplacement ciblé dans un fichier de projet Orion.
 */
public final class FileLocation {

    public final String filename;
    public final int line;
    public final String snippet;

    public FileLocation(String filename, int line, String snippet) {
        this.filename = filename == null ? "" : filename;
        this.line = line;
        this.snippet = snippet == null ? "" : snippet;
    }

    public String toPromptBlock() {
        if (line < 0) return "Fichier : " + filename + "\n" + snippet;
        return "Fichier : " + filename + " · Ligne " + line + "\n" + snippet;
    }
}
