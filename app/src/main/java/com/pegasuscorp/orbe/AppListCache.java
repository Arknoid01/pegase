package com.pegasuscorp.orbe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cache la liste des apps launcher pour ouvrir le tiroir instantanément.
 * Les icônes sont en SoftReference — reclaimables sous pression mémoire.
 */
public final class AppListCache {

    public static final class AppEntry {
        public final String label;
        public final String pkg;
        public final String activity;
        private SoftReference<Drawable> iconRef;

        AppEntry(String label, String pkg, String activity, Drawable icon) {
            this.label = label;
            this.pkg = pkg;
            this.activity = activity;
            if (icon != null) {
                this.iconRef = new SoftReference<>(icon);
            }
        }

        /** Icône (recharge si GC a repris la SoftReference). */
        public Drawable icon(Context context) {
            Drawable d = iconRef != null ? iconRef.get() : null;
            if (d != null) return d;
            if (context == null) return null;
            synchronized (this) {
                d = iconRef != null ? iconRef.get() : null;
                if (d != null) return d;
                try {
                    PackageManager pm = context.getPackageManager();
                    Intent launch = new Intent(Intent.ACTION_MAIN);
                    launch.addCategory(Intent.CATEGORY_LAUNCHER);
                    launch.setClassName(pkg, activity);
                    ResolveInfo ri = pm.resolveActivity(launch, 0);
                    if (ri != null) {
                        d = ri.loadIcon(pm);
                    } else {
                        d = pm.getApplicationIcon(pkg);
                    }
                } catch (Exception e) {
                    d = null;
                }
                if (d != null) iconRef = new SoftReference<>(d);
                return d;
            }
        }

        void clearIconRef() {
            iconRef = null;
        }
    }

    public interface LoadCallback {
        void onLoaded(List<AppEntry> apps);
    }

    private static volatile List<AppEntry> cached;
    private static volatile boolean loading;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<LoadCallback> pendingCallbacks = new ArrayList<>();

    private AppListCache() {}

    /** Précharge en arrière-plan dès l'écran d'accueil. */
    public static void warmUp(Context context) {
        if (cached != null || loading) return;
        loadAsync(context.getApplicationContext(), null);
    }

    /** Recharge les icônes système (après mutation accidentelle du cache). */
    public static void invalidate() {
        synchronized (AppListCache.class) {
            cached = null;
            loading = false;
            pendingCallbacks.clear();
        }
    }

    /** Sous pression mémoire : lâche les bitmaps d'icônes, garde les métadonnées. */
    public static void trimIcons() {
        List<AppEntry> list = cached;
        if (list == null) return;
        for (AppEntry e : list) {
            if (e != null) e.clearIconRef();
        }
    }

    public static List<AppEntry> getCached() {
        return cached;
    }

    public static void loadAsync(Context context, LoadCallback callback) {
        Context app = context.getApplicationContext();
        if (cached != null) {
            if (callback != null) callback.onLoaded(cached);
            return;
        }
        synchronized (AppListCache.class) {
            if (loading) {
                if (callback != null) {
                    pendingCallbacks.add(callback);
                }
                return;
            }
            loading = true;
            if (callback != null) pendingCallbacks.add(callback);
        }
        new Thread(() -> {
            List<AppEntry> result;
            try {
                result = buildList(app);
            } catch (Throwable t) {
                android.util.Log.e("AppListCache", "Échec chargement apps", t);
                result = Collections.emptyList();
            }
            final List<AppEntry> loaded = result;
            final List<LoadCallback> cbs;
            synchronized (AppListCache.class) {
                cached = loaded;
                loading = false;
                cbs = new ArrayList<>(pendingCallbacks);
                pendingCallbacks.clear();
            }
            MAIN.post(() -> {
                for (LoadCallback cb : cbs) {
                    if (cb != null) cb.onLoaded(loaded);
                }
            });
        }, "AppListCache").start();
    }

    private static List<AppEntry> buildList(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);

        String selfPkg = context.getPackageName();
        List<AppEntry> apps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(selfPkg)) continue;
            CharSequence labelCs = ri.loadLabel(pm);
            String label = labelCs != null ? labelCs.toString().trim() : "";
            if (label.isEmpty()) label = pkg;
            String activity = ri.activityInfo.name != null ? ri.activityInfo.name : pkg;
            apps.add(new AppEntry(label, pkg, activity, ri.loadIcon(pm)));
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }
}
