package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.ShortcutStore;
import com.pegasuscorp.orbe.tools.ToolTag;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Ouvre une application installée ou un raccourci web orbe (libellé personnalisé).
 */
public final class OpenAppTool implements Tool {

    /** Nom demandé (FR) → libellés EN / système à matcher. */
    private static final Map<String, String[]> ALIASES = new HashMap<>();

    static {
        ALIASES.put("horloge", new String[]{"horloge", "clock", "alarm clock", "deskclock"});
        ALIASES.put("agenda", new String[]{"agenda", "calendar", "calendrier"});
        ALIASES.put("calendrier", new String[]{"calendrier", "calendar", "agenda"});
        ALIASES.put("clock", new String[]{"clock", "alarm clock", "horloge"});
        ALIASES.put("alarm clock", new String[]{"alarm clock", "clock", "horloge"});
        ALIASES.put("calendar", new String[]{"calendar", "agenda", "calendrier"});
        ALIASES.put("chrome", new String[]{"chrome", "google chrome"});
        ALIASES.put("google chrome", new String[]{"google chrome", "chrome"});
        ALIASES.put("brave", new String[]{"brave", "brave browser"});
        ALIASES.put("brave browser", new String[]{"brave browser", "brave"});
    }

    /** Résultat d'un lancement sans callback (séquences UI). */
    public static final class LaunchResult {
        public final boolean ok;
        public final String packageName;
        public final String label;
        public final String error;

        public LaunchResult(boolean ok, String packageName, String label, String error) {
            this.ok = ok;
            this.packageName = packageName != null ? packageName : "";
            this.label = label != null ? label : "";
            this.error = error != null ? error : "";
        }

        public static LaunchResult success(String packageName, String label) {
            return new LaunchResult(true, packageName, label, "");
        }

        public static LaunchResult fail(String error) {
            return new LaunchResult(false, "", "", error);
        }
    }

    @Override public String id() { return "open_app"; }

    @Override public ToolTag tag() { return ToolTag.OPEN_APP; }

    @Override
    public String description() {
        return "open_app(name:str) — Ouvre une application (ex: \"Spotify\", \"Brave\") "
                + "ou un raccourci web de l'orbe par son libellé (ex: \"Cursor\"). "
                + "Alias FR : Horloge = Clock ; Agenda = Calendar. "
                + "Si la phrase enchaîne aussi un clic/saisie : préfère "
                + "ui_action steps=[{action:open,name:\"Brave\"},{action:click,...}] "
                + "(name libellé, PAS package avec points) plutôt que open_app seul.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String name = params.optString("name", "").trim();
        if (name.isEmpty()) {
            cb.onError("Quelle application veux-tu ouvrir ?");
            return;
        }
        LaunchResult r = launchApp(ctx, name);
        if (!r.ok) {
            cb.onError(r.error);
            return;
        }
        cb.onSuccessAndExit(ToolResult.text("J'ouvre " + r.label + "."));
    }

    /**
     * Lance une app / raccourci sans terminer le tour agentique —
     * pour les séquences {@code ui_action.steps}.
     */
    public static LaunchResult launchApp(Context ctx, String name) {
        if (ctx == null || name == null || name.trim().isEmpty()) {
            return LaunchResult.fail("Quelle application veux-tu ouvrir ?");
        }
        String want = normalizeAppQuery(name);

        // Package explicite (com.android.chrome) — y compris après collapse espaces LLM
        if (looksLikePackageName(want)) {
            LaunchResult byPkg = launchPackage(ctx, want, want);
            if (byPkg.ok) return byPkg;
            // Package faux / non installé → dernier segment (chrome, brave…)
            int lastDot = want.lastIndexOf('.');
            if (lastDot >= 0 && lastDot < want.length() - 1) {
                want = want.substring(lastDot + 1);
            }
        }

        ShortcutStore.Slot web = ShortcutStore.findWebByLabel(ctx, want);
        if (web != null) {
            String url = forceBrowserNavigate(ShortcutStore.normalizeUrl(web.url));
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            // Préfère Brave s'il est installé (navigateur par défaut du projet).
            String bravePkg = preferredBrowserPackage(ctx);
            if (bravePkg != null) {
                view.setPackage(bravePkg);
            }
            try {
                ctx.startActivity(view);
                return LaunchResult.success(bravePkg != null ? bravePkg : "", web.label);
            } catch (Exception e) {
                if (bravePkg != null) {
                    try {
                        view.setPackage(null);
                        ctx.startActivity(view);
                        return LaunchResult.success("", web.label);
                    } catch (Exception e2) {
                        return LaunchResult.fail("Impossible d'ouvrir le lien « " + web.label + " ».");
                    }
                }
                return LaunchResult.fail("Impossible d'ouvrir le lien « " + web.label + " ».");
            }
        }

        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);

        String[] needles = resolveNeedles(want);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String appName = ri.loadLabel(pm).toString();
            String appLower = appName.toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (appLower.contains(needle)) {
                    String pkg = ri.activityInfo.packageName;
                    return launchPackage(ctx, pkg, appName);
                }
            }
        }
        // Package-like qui a échoué : message plus clair avec forme normalisée
        return LaunchResult.fail("Je n'ai pas trouvé l'application " + want + ".");
    }

    /**
     * Les LLM mettent souvent des espaces dans les packages
     * ({@code com. android. chrome} → {@code com.android.chrome}).
     */
    static String normalizeAppQuery(String raw) {
        if (raw == null) return "";
        String want = raw.trim();
        if (want.isEmpty()) return want;
        if (want.indexOf('.') >= 0 && want.matches("(?i)^[a-z0-9_./\\- ]+$")) {
            String collapsed = want
                    .replaceAll("\\s*\\.\\s*", ".")
                    .replaceAll("\\s+", "");
            if (looksLikePackageName(collapsed)) {
                return collapsed.toLowerCase(Locale.ROOT);
            }
        }
        return want;
    }

    static boolean looksLikePackageName(String s) {
        if (s == null || s.isEmpty() || s.contains(" ")) return false;
        return s.matches("(?i)^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");
    }

    private static LaunchResult launchPackage(Context ctx, String packageName, String label) {
        PackageManager pm = ctx.getPackageManager();
        Intent launch = pm.getLaunchIntentForPackage(packageName);
        if (launch == null) {
            return LaunchResult.fail("Impossible de lancer « " + label + " ».");
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(launch);
            return LaunchResult.success(packageName, label);
        } catch (Exception e) {
            return LaunchResult.fail("Impossible d'ouvrir « " + label + " ».");
        }
    }

    private static String[] resolveNeedles(String name) {
        String key = name.toLowerCase(Locale.ROOT).trim();
        String[] aliased = ALIASES.get(key);
        if (aliased != null) return aliased;
        return new String[]{key};
    }

    /**
     * Fragment unique pour forcer le navigateur à naviguer même si l'URL
     * est déjà ouverte en arrière-plan (sinon l'activité est souvent no-op).
     */
    static String forceBrowserNavigate(String url) {
        if (url == null || url.isEmpty()) return url;
        String base = url;
        int hash = base.indexOf('#');
        if (hash >= 0) base = base.substring(0, hash);
        return base + "#pegase=" + System.currentTimeMillis();
    }

    /** Brave d'abord, puis Chrome — null si aucun navigateur connu. */
    static String preferredBrowserPackage(Context ctx) {
        if (ctx == null) return null;
        PackageManager pm = ctx.getPackageManager();
        String[] candidates = {
                "com.brave.browser",
                "com.brave.browser_nightly",
                "com.android.chrome"
        };
        for (String pkg : candidates) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return null;
    }
}
