package com.pegasuscorp.orbe.pcshell;

/**
 * Commande vocale en attente — exécutée après déverrouillage biométrique du terminal.
 */
public final class PcShellVoicePending {

    private static String pendingCommandId;

    private PcShellVoicePending() {}

    public static synchronized void setExecuteAfterUnlock(String commandId) {
        pendingCommandId = commandId != null && !commandId.isEmpty() ? commandId : null;
    }

    public static synchronized String takePendingCommandId() {
        String id = pendingCommandId;
        pendingCommandId = null;
        return id;
    }

    public static synchronized void resetForTests() {
        pendingCommandId = null;
    }
}
