package com.pegasuscorp.orbe.memory;

/**
 * Résultat d'une modification de mémoire demandée à la voix.
 */
public class MemoryEditResult {

    public final boolean success;
    /** true = poursuivre comme une discussion normale */
    public final boolean fallbackToChat;
    public final String toastMessage;
    public final String spokenReply;

    private MemoryEditResult(boolean success, boolean fallbackToChat,
                             String toastMessage, String spokenReply) {
        this.success = success;
        this.fallbackToChat = fallbackToChat;
        this.toastMessage = toastMessage;
        this.spokenReply = spokenReply;
    }

    public static MemoryEditResult applied(String toast, String spoken) {
        return new MemoryEditResult(true, false, toast, spoken);
    }

    public static MemoryEditResult failed(String spoken) {
        return new MemoryEditResult(false, false, null, spoken);
    }

    public static MemoryEditResult notMemoryEdit() {
        return new MemoryEditResult(false, true, null, null);
    }
}
