package com.pegasuscorp.orbe.session;

import android.app.ActivityManager;
import android.content.Context;

/**
 * Pression mémoire appareil — pour alléger le retour HOME (évite que le LMK
 * tue les apps en arrière-plan pendant le spike Orbe).
 */
public final class MemoryPressure {

    private MemoryPressure() {}

    /** true si Android signale lowMemory ou avail &lt; ~15 % du total. */
    public static boolean isLow(Context ctx) {
        if (ctx == null) return false;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            if (info.lowMemory) return true;
            if (info.totalMem > 0 && info.availMem < info.totalMem / 7) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
