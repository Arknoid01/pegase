package com.pegasuscorp.orbe.orion;

/** Volume réseau RunPod (lié à un data center). */
public final class NetworkVolume {

    public final String id;
    public final String name;
    public final String dataCenterId;
    public final int sizeGb;

    public NetworkVolume(String id, String name, String dataCenterId, int sizeGb) {
        this.id = id != null ? id : "";
        this.name = name != null ? name : "";
        this.dataCenterId = dataCenterId != null ? dataCenterId : "";
        this.sizeGb = sizeGb;
    }

    public String label() {
        StringBuilder sb = new StringBuilder();
        if (!name.isEmpty()) sb.append(name).append(" · ");
        sb.append(id);
        if (!dataCenterId.isEmpty()) sb.append(" (").append(dataCenterId).append(')');
        if (sizeGb > 0) sb.append(" · ").append(sizeGb).append(" Go");
        return sb.toString();
    }
}
