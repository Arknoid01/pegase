package com.pegasuscorp.orbe.pcshell;

/**
 * Passage de gros textes vers {@link PcShellOutputActivity} (évite TransactionTooLargeException).
 */
public final class PcShellOutputHolder {

    public static final String EXTRA_TITLE = "title";

    private static String pendingTitle;
    private static String pendingBody;

    private PcShellOutputHolder() {}

    public static synchronized void put(String title, String body) {
        pendingTitle = title != null ? title : "";
        pendingBody = body != null ? body : "";
    }

    public static synchronized String takeTitle() {
        String t = pendingTitle != null ? pendingTitle : "";
        pendingTitle = null;
        return t;
    }

    public static synchronized String takeBody() {
        String b = pendingBody != null ? pendingBody : "";
        pendingBody = null;
        return b;
    }

    public static synchronized void resetForTests() {
        pendingTitle = null;
        pendingBody = null;
    }
}
