package com.pegasuscorp.orbe.copilot;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Choisit la racine a11y de l'app sous-jacente, pas l'overlay Pégase.
 * {@link AccessibilityService#getRootInActiveWindow()} pointe souvent sur la bulle
 * copilote quand elle a le focus — ce qui polluait le snapshot et cassait ui_*.
 */
public final class A11yRootPicker {

    private A11yRootPicker() {}

    /**
     * Racine préférée pour analyse / actions UI.
     * Caller doit {@link AccessibilityNodeInfo#recycle()} le résultat.
     */
    public static AccessibilityNodeInfo preferAppRoot(AccessibilityService svc) {
        if (svc == null) return null;
        String self = svc.getPackageName();

        AccessibilityNodeInfo active = svc.getRootInActiveWindow();
        if (isUsableAppRoot(svc, active, self)) {
            return active;
        }
        if (active != null) active.recycle();

        List<AccessibilityWindowInfo> windows = svc.getWindows();
        if (windows != null && !windows.isEmpty()) {
            AccessibilityNodeInfo best = null;
            int bestScore = Integer.MIN_VALUE;
            for (AccessibilityWindowInfo w : windows) {
                if (w == null) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                if (!isUsableAppRoot(svc, root, self)) {
                    root.recycle();
                    continue;
                }
                int score = scoreWindow(w);
                if (score > bestScore) {
                    if (best != null) best.recycle();
                    best = root;
                    bestScore = score;
                } else {
                    root.recycle();
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    public static String packageOf(AccessibilityNodeInfo root) {
        if (root == null) return "";
        CharSequence p = root.getPackageName();
        return p != null ? p.toString() : "";
    }

    private static boolean isUsableAppRoot(AccessibilityService svc,
            AccessibilityNodeInfo root, String self) {
        if (root == null) return false;
        String pkg = packageOf(root);
        if (pkg.isEmpty() || pkg.equals(self)) return false;
        return CopilotPrefs.isPackageAllowed(svc, pkg);
    }

    private static int scoreWindow(AccessibilityWindowInfo w) {
        int score = 0;
        if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) score += 30;
        else if (w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM) score -= 40;
        if (w.isActive()) score += 10;
        if (w.isFocused()) score += 5;
        return score;
    }
}
