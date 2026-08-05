package com.pegasuscorp.orbe.copilot;

import android.graphics.Rect;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Rematch SightSync-lite avant clic — classe dédiée pour tests JVM sans init A11yUiExecutor.
 */
public final class A11yClickRematch {

    public static final int BOUNDS_TOLERANCE_PX = 48;

    private A11yClickRematch() {}

    public static boolean stillMatches(A11yUiMatcher.Target preview,
            AccessibilityNodeInfo node, Rect live) {
        if (preview == null || node == null || live == null) return true;
        CharSequence t = node.getContentDescription();
        if (t == null || t.length() == 0) t = node.getText();
        String liveLabel = t != null ? t.toString() : "";
        return stillMatches(preview, liveLabel, node.getViewIdResourceName(), live);
    }

    public static boolean stillMatches(A11yUiMatcher.Target preview,
            String liveLabel, String liveViewId, Rect live) {
        if (preview == null || live == null) return true;
        int previewCx = (preview.left + preview.right) / 2;
        int previewCy = preview.bottom > preview.top
                ? (preview.top + preview.bottom) / 2
                : preview.top;
        int liveCx = live.centerX();
        int liveCy = live.height() > 0 ? live.centerY() : live.top;
        if (Math.abs(previewCx - liveCx) > BOUNDS_TOLERANCE_PX
                || Math.abs(previewCy - liveCy) > BOUNDS_TOLERANCE_PX * 2) {
            return false;
        }
        if (!TextUtils.isEmpty(preview.text)) {
            String want = A11yUiMatcher.normalizeForMatch(preview.text);
            String got = A11yUiMatcher.normalizeForMatch(liveLabel);
            if (!want.isEmpty() && !got.isEmpty()
                    && !got.contains(want) && !want.contains(got)) {
                if (!TextUtils.isEmpty(preview.viewId) && liveViewId != null
                        && liveViewId.contains(preview.viewId)) {
                    return true;
                }
                return false;
            }
        }
        return true;
    }
}
