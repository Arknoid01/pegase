package com.pegasuscorp.orbe.intentions.location;

/**
 * Quand allumer la puce GPS (logique pure, testable).
 *
 * Le GPS en continu coûtait ~1 Ah/jour (relevé Jauge 2026-11-10). On suit donc la position
 * par le réseau (Wi-Fi/antennes, quasi gratuit) et on n'allume le GPS que si l'on semble
 * rouler, le temps d'avoir une vraie vitesse pour le mode conduite.
 */
public final class LocationGpsPolicy {

    /** Coupure du GPS après ce délai sans vitesse de conduite. */
    public static final long GPS_IDLE_OFF_MS = 5L * 60L * 1000L;
    /** Écart minimal entre deux relevés réseau pour estimer une vitesse. */
    static final long MIN_DERIVE_DT_MS = 20_000L;
    /** Au-delà, les deux relevés sont trop éloignés dans le temps pour parler de vitesse. */
    static final long MAX_DERIVE_DT_MS = 10L * 60L * 1000L;

    private LocationGpsPolicy() {}

    /**
     * Vitesse estimée entre deux relevés (m/s), 0 si le déplacement reste dans
     * l'incertitude cumulée des deux positions (sauts d'antenne).
     */
    public static float derivedSpeedMps(float distanceM, long dtMs, float accPrevM, float accCurM) {
        if (dtMs < MIN_DERIVE_DT_MS || dtMs > MAX_DERIVE_DT_MS) return 0f;
        float uncertainty = Math.max(0f, accPrevM) + Math.max(0f, accCurM);
        if (distanceM <= uncertainty) return 0f;
        return (distanceM - uncertainty) / (dtMs / 1000f);
    }

    /** Seuil de réveil du GPS : un peu sous le seuil d'entrée en conduite. */
    public static float wakeKmh(float enterKmh) {
        return Math.max(10f, enterKmh * 0.6f);
    }

    /**
     * @param lastFastMs dernier instant où la vitesse dépassait le seuil de sortie (0 = jamais)
     */
    public static boolean shouldGpsBeOn(boolean autoDriveEnabled, boolean networkAvailable,
                                        boolean driveActive, float speedKmh, float enterKmh,
                                        long lastFastMs, long nowMs, boolean gpsOn) {
        if (!networkAvailable) return true; // seule source possible
        if (!autoDriveEnabled) return false; // le GPS ne sert qu'au mode conduite
        if (driveActive) return true;
        if (speedKmh >= wakeKmh(enterKmh)) return true;
        return gpsOn && lastFastMs > 0L && nowMs - lastFastMs < GPS_IDLE_OFF_MS;
    }
}
