package com.pegasuscorp.orbe.orion.search;

/**
 * Emplacement AST (méthode, champ…) dans un fichier Java projet Orion.
 */
public final class CodeLocation {

    public final String filename;
    public final String methodName;
    public final int startLine;
    public final int endLine;
    public final String snippet;
    public final String kind;

    public CodeLocation(String filename, String methodName, int startLine, int endLine,
            String snippet, String kind) {
        this.filename = filename == null ? "" : filename;
        this.methodName = methodName == null ? "" : methodName;
        this.startLine = startLine;
        this.endLine = endLine;
        this.snippet = snippet == null ? "" : snippet;
        this.kind = kind == null ? "method" : kind;
    }

    public String toPromptBlock() {
        return "Fichier : " + filename + "\n"
                + kind + " : " + methodName
                + " (lignes " + startLine + "–" + endLine + ")\n"
                + snippet;
    }
}
