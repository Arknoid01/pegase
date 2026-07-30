package com.pegasuscorp.orbe.orion;

/**
 * Offre GPU RunPod (liste filtrable).
 */
public final class GpuOffer {
    public final String id;
    public final String displayName;
    public final int vramGb;
    public final float pricePerHour;
    public final boolean available;

    public GpuOffer(String id, String displayName, int vramGb, float pricePerHour,
            boolean available) {
        this.id = id;
        this.displayName = displayName != null ? displayName : id;
        this.vramGb = vramGb;
        this.pricePerHour = pricePerHour;
        this.available = available;
    }

    public String shortLabel() {
        StringBuilder sb = new StringBuilder();
        sb.append(displayName);
        if (vramGb > 0) sb.append(", ").append(vramGb).append("GB");
        sb.append(String.format(java.util.Locale.US, ", $%.2f/h", pricePerHour));
        return sb.toString();
    }
}
