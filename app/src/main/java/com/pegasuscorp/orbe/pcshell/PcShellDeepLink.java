package com.pegasuscorp.orbe.pcshell;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

/**
 * Deep links Terminal PC — {@code pegase://pc-shell/run?autoRunCommand=...}.
 */
public final class PcShellDeepLink {

    private PcShellDeepLink() {}

    public static void captureAutoRun(Context context, Intent intent) {
        if (intent == null) return;
        String slug = intent.getStringExtra(PcShellAutoRunPending.EXTRA_AUTO_RUN_COMMAND);
        if (TextUtils.isEmpty(slug)) {
            slug = slugFromUri(intent.getData());
        }
        if (!TextUtils.isEmpty(slug)) {
            PcShellAutoRunPending.setAfterUnlock(context, slug);
            intent.removeExtra(PcShellAutoRunPending.EXTRA_AUTO_RUN_COMMAND);
            intent.setData(null);
        }
    }

    static String slugFromUri(Uri data) {
        if (data == null) return null;
        if (!"pegase".equals(data.getScheme()) || !"pc-shell".equals(data.getHost())) {
            return null;
        }
        String path = data.getPath();
        if (path == null || !path.startsWith("/run")) return null;
        return data.getQueryParameter("autoRunCommand");
    }
}
