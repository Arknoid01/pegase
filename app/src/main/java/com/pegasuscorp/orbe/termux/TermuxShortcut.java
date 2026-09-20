package com.pegasuscorp.orbe.termux;

/** Un raccourci Termux : libellé affiché + commande shell à exécuter. */
public final class TermuxShortcut {

    public final String label;
    public final String command;

    public TermuxShortcut(String label, String command) {
        this.label = label;
        this.command = command;
    }
}
