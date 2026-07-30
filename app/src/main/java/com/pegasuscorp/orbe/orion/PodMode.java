package com.pegasuscorp.orbe.orion;

/** Mode du pod RunPod courant (Orion = Ollama, Comfy = ComfyUI). */
public enum PodMode {
    ORION,
    COMFY;

    public String label() {
        return this == COMFY ? "Comfy" : "Orion";
    }

    public static PodMode fromPersisted(String raw) {
        if (raw == null) return ORION;
        try {
            return PodMode.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return ORION;
        }
    }
}
