package com.pegasuscorp.orbe.orion;

/**
 * Étapes de démarrage Orion / Comfy (chargement visible + diagnostic si blocage).
 */
public final class OrionLoadStep {

    public static final int TOTAL = 5;

    public static final int CONFIRM = 1;
    public static final int START_POD = 2;
    public static final int WAIT_POD = 3;
    /** Phase service : Ollama (Orion) ou ComfyUI 8188 (Comfy). */
    public static final int WAIT_OLLAMA = 4;
    public static final int READY = 5;

    private OrionLoadStep() {}

    public static String label(int step) {
        return label(step, PodMode.ORION);
    }

    public static String label(int step, PodMode mode) {
        boolean comfy = mode == PodMode.COMFY;
        switch (step) {
            case CONFIRM:
                return "Confirmation coût";
            case START_POD:
                return comfy ? "Démarrage pod Comfy" : "Démarrage pod RunPod";
            case WAIT_POD:
                return "Pod s'allume (cold start)";
            case WAIT_OLLAMA:
                return comfy
                        ? "ComfyUI (pip + page 8188)"
                        : "Modèle Ollama (~19 Go / VRAM)";
            case READY:
                return comfy ? "Comfy prêt" : "Orion prêt";
            default:
                return comfy ? "Comfy" : "Orion";
        }
    }

    public static String line(int step, int total, String detail) {
        return line(step, total, detail, PodMode.ORION);
    }

    public static String line(int step, int total, String detail, PodMode mode) {
        String base = step + "/" + total + " " + label(step, mode);
        if (detail == null || detail.trim().isEmpty()) return base;
        return base + " · " + shortenDetail(detail.trim());
    }

    /** Garde la barre de statut lisible (1–2 lignes). */
    private static String shortenDetail(String detail) {
        String d = detail;
        String low = d.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("502") || low.contains("bad gateway")) {
            return "proxy 502 — boot en cours";
        }
        if (low.contains("pull") && low.contains("%")) {
            return d.length() > 48 ? d.substring(0, 45) + "…" : d;
        }
        if (d.length() > 56) return d.substring(0, 53) + "…";
        return d;
    }
}
