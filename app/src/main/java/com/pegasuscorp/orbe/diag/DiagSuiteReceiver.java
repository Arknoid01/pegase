package com.pegasuscorp.orbe.diag;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Lance une suite diag via adb :
 * {@code adb shell am broadcast -n com.pegasuscorp.orbe/.diag.DiagSuiteReceiver
 *   -a com.pegasuscorp.orbe.action.RUN_DIAG_SUITE --es suite tags}
 */
public final class DiagSuiteReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.pegasuscorp.orbe.action.RUN_DIAG_SUITE";
    private static final String TAG = "DiagSuiteReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!ACTION.equals(intent.getAction())
                && !"android.intent.action.VIEW".equals(intent.getAction())) {
            // Explicit component start without action still OK
        }
        String suite = intent.getStringExtra("suite");
        if (suite == null) suite = "tags";
        Context app = context.getApplicationContext();
        Log.i(TAG, "run suite=" + suite);
        if ("tags".equalsIgnoreCase(suite) || "tags_verify".equalsIgnoreCase(suite)) {
            DiagScriptRunner.get().runTagsSuite(app, logListener(suite));
        } else {
            DiagScriptRunner.get().runMiniSuite(app, logListener(suite));
        }
    }

    private static DiagScriptRunner.Listener logListener(String suite) {
        return new DiagScriptRunner.Listener() {
            @Override
            public void onProgress(int index, int total, String label, String phase) {
                Log.i(TAG, suite + " [" + (index + 1) + "/" + total + "] "
                        + (label != null ? label : "") + " — " + phase);
            }

            @Override
            public void onComplete(DiagScriptResult result) {
                Log.i(TAG, suite + " DONE " + (result != null ? result.summaryLine() : "?"));
            }

            @Override
            public void onCannotStart(String reason) {
                Log.w(TAG, suite + " BLOCKED: " + reason);
            }
        };
    }
}
