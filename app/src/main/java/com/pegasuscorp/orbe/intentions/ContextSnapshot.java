package com.pegasuscorp.orbe.intentions;

/**
 * Instantané local pour les règles (pas de réseau).
 */
public final class ContextSnapshot {

    public final int batteryPercent;
    public final boolean charging;
    public final String ssid;
    public final int lastSeenBatteryPercent;
    public final String lastSeenSsid;
    public final String workWifiSsid;
    public final boolean carBtConnected;
    public final boolean lastSeenCarBtConnected;
    public final boolean briefReadyEvent;
    public final long nowMs;

    public ContextSnapshot(int batteryPercent, boolean charging, String ssid,
            int lastSeenBatteryPercent, String lastSeenSsid, String workWifiSsid,
            boolean carBtConnected, boolean lastSeenCarBtConnected,
            boolean briefReadyEvent, long nowMs) {
        this.batteryPercent = batteryPercent;
        this.charging = charging;
        this.ssid = ssid == null ? "" : ssid;
        this.lastSeenBatteryPercent = lastSeenBatteryPercent;
        this.lastSeenSsid = lastSeenSsid == null ? "" : lastSeenSsid;
        this.workWifiSsid = workWifiSsid == null ? "" : workWifiSsid;
        this.carBtConnected = carBtConnected;
        this.lastSeenCarBtConnected = lastSeenCarBtConnected;
        this.briefReadyEvent = briefReadyEvent;
        this.nowMs = nowMs;
    }
}
