package com.pegasuscorp.orbe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Détection et chargement des packs d'icônes installés (Nova, Apex, ADW, etc.).
 */
public final class IconPackManager {

    public static final class PackInfo {
        public final String packageName;
        public final String label;
        public final Drawable icon;

        PackInfo(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private static final Map<String, Map<String, String>> APP_FILTER_CACHE = new HashMap<>();

    private IconPackManager() {}

    public static List<PackInfo> discoverInstalled(Context context) {
        PackageManager pm = context.getPackageManager();
        Set<String> seen = new HashSet<>();
        List<PackInfo> packs = new ArrayList<>();

        Intent[] probes = {
                new Intent("com.novalauncher.THEME"),
                intentWithCategory("com.anddoes.launcher.THEME"),
                intentWithCategory("com.teslacoilsw.launcher.THEME"),
                intentWithCategory("com.gau.go.launcherex.theme"),
                intentWithCategory("org.adw.launcher.THEMES"),
                intentWithCategory("org.adw.launcher.icons.THEMES"),
                new Intent("org.adw.launcher.icons.ACTION_PICK_ICON"),
        };

        for (Intent probe : probes) {
            List<ResolveInfo> infos = pm.queryIntentActivities(probe, PackageManager.GET_META_DATA);
            for (ResolveInfo ri : infos) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(context.getPackageName()) || !seen.add(pkg)) continue;
                packs.add(new PackInfo(
                        pkg,
                        ri.loadLabel(pm).toString(),
                        ri.loadIcon(pm)));
            }
        }

        Collections.sort(packs, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return packs;
    }

    public static Drawable loadIcon(Context context, String iconPackPkg, String targetPkg,
                                    String targetActivity, Drawable fallback) {
        if (iconPackPkg == null || iconPackPkg.isEmpty()) return fallback;
        try {
            PackageManager pm = context.getPackageManager();
            Resources res = pm.getResourcesForApplication(iconPackPkg);
            Map<String, String> filter = getAppFilter(context, iconPackPkg, res);

            String componentKey = "ComponentInfo{" + targetPkg + "/" + targetActivity + "}";
            String drawableName = filter.get(componentKey);
            if (drawableName == null) drawableName = filter.get(targetPkg);
            if (drawableName == null) drawableName = filter.get(targetPkg.toLowerCase(Locale.ROOT));

            int id = 0;
            if (drawableName != null) {
                id = res.getIdentifier(drawableName, "drawable", iconPackPkg);
            }
            if (id == 0) {
                id = res.getIdentifier(targetPkg, "drawable", iconPackPkg);
            }
            if (id == 0) {
                id = res.getIdentifier(targetPkg.replace('.', '_'), "drawable", iconPackPkg);
            }
            if (id == 0 && targetActivity != null) {
                String classKey = targetActivity.contains(".")
                        ? targetActivity.substring(targetActivity.lastIndexOf('.') + 1)
                        : targetActivity;
                id = res.getIdentifier(classKey.toLowerCase(Locale.ROOT), "drawable", iconPackPkg);
            }
            if (id != 0) {
                return res.getDrawable(id, null);
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    public static Drawable loadIconForPackage(Context context, String iconPackPkg,
                                              String targetPkg, Drawable fallback) {
        String activity = targetPkg;
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(targetPkg);
            if (launch != null && launch.getComponent() != null) {
                activity = launch.getComponent().getClassName();
            }
        } catch (Exception ignored) {
        }
        return loadIcon(context, iconPackPkg, targetPkg, activity, fallback);
    }

    private static Intent intentWithCategory(String category) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category);
        return intent;
    }

    private static Map<String, String> getAppFilter(Context context, String iconPackPkg,
                                                    Resources res) {
        Map<String, String> cached = APP_FILTER_CACHE.get(iconPackPkg);
        if (cached != null) return cached;

        Map<String, String> map = new HashMap<>();
        parseAppFilter(res, "appfilter.xml", map);
        parseAppFilter(res, "appfilter_alt.xml", map);
        APP_FILTER_CACHE.put(iconPackPkg, map);
        return map;
    }

    private static void parseAppFilter(Resources res, String assetName, Map<String, String> out) {
        try (InputStream in = res.getAssets().open(assetName)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, "UTF-8");
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "item".equals(parser.getName())) {
                    String component = parser.getAttributeValue(null, "component");
                    String drawable = parser.getAttributeValue(null, "drawable");
                    if (component != null && drawable != null) {
                        out.put(component, drawable);
                        String pkg = extractPackage(component);
                        if (pkg != null && !out.containsKey(pkg)) {
                            out.put(pkg, drawable);
                        }
                    }
                }
                event = parser.next();
            }
        } catch (Exception ignored) {
        }
    }

    private static String extractPackage(String component) {
        int start = component.indexOf('{');
        int slash = component.indexOf('/');
        if (start >= 0 && slash > start) {
            return component.substring(start + 1, slash);
        }
        return null;
    }

    public static void clearCache() {
        APP_FILTER_CACHE.clear();
    }
}
