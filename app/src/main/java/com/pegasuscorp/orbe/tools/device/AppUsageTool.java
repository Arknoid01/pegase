package com.pegasuscorp.orbe.tools.device;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.ToolTag;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Temps d'écran par appli sur une période (accès spécial « données d'utilisation »). */
public final class AppUsageTool implements Tool {

    @Override
    public String id() {
        return "app_usage";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.APP_USAGE;
    }

    @Override
    public String description() {
        return "app_usage(hours?:int, limit?:int) — Applis les plus utilisées (temps au premier "
                + "plan) sur les N dernières heures (défaut 24, max 168). Pour « combien de temps "
                + "j'ai passé sur… », « mes applis les plus utilisées ».";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        if (!hasUsageAccess(ctx)) {
            cb.onError("Autorise l'accès aux données d'utilisation à Pégase : Réglages > "
                    + "Applications > Accès spécial > Accès aux données d'utilisation.");
            return;
        }
        int hours = Math.max(1, Math.min(168, params.optInt("hours", 24)));
        int limit = Math.max(1, Math.min(15, params.optInt("limit", 8)));
        long end = System.currentTimeMillis();
        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        List<UsageStats> stats = usm != null
                ? usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST,
                        end - hours * 3_600_000L, end)
                : null;
        cb.onSuccess(ToolResult.text(format(ctx, stats, hours, limit)));
    }

    private static String format(Context ctx, List<UsageStats> stats, int hours, int limit) {
        List<UsageStats> used = new ArrayList<>();
        if (stats != null) {
            for (UsageStats s : stats) {
                if (s.getTotalTimeInForeground() >= 60_000L
                        && !s.getPackageName().equals(ctx.getPackageName())) {
                    used.add(s);
                }
            }
        }
        if (used.isEmpty()) return "Aucune utilisation notable sur les " + hours + " dernières heures.";
        Collections.sort(used, (a, b) ->
                Long.compare(b.getTotalTimeInForeground(), a.getTotalTimeInForeground()));
        StringBuilder sb = new StringBuilder("Temps d'écran sur " + hours + " h :");
        for (int i = 0; i < Math.min(limit, used.size()); i++) {
            UsageStats s = used.get(i);
            sb.append("\n- ").append(label(ctx, s.getPackageName())).append(" : ")
                    .append(duration(s.getTotalTimeInForeground()));
        }
        return sb.toString();
    }

    static String duration(long ms) {
        long min = ms / 60_000L;
        if (min < 60) return min + " min";
        return (min / 60) + " h " + String.format(java.util.Locale.ROOT, "%02d", min % 60);
    }

    private static String label(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(info));
        } catch (Exception e) {
            return pkg;
        }
    }

    private static boolean hasUsageAccess(Context ctx) {
        AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) return false;
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), ctx.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }
}
