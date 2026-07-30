package com.pegasuscorp.orbe.copilot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Activité transparente pour obtenir le consentement MediaProjection.
 * Se ferme immédiatement après la réponse système.
 */
public final class ScreenCapturePermissionActivity extends Activity {

    private static final int REQ_CAPTURE = 8801;

    public interface PermissionListener {
        void onResult(boolean granted);
    }

    private static final CopyOnWriteArrayList<PermissionListener> PENDING =
            new CopyOnWriteArrayList<>();

    public static void request(Context ctx, PermissionListener listener) {
        if (listener != null) PENDING.add(listener);
        Intent i = new Intent(ctx, ScreenCapturePermissionActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            finishWith(false);
            return;
        }
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) {
            finishWith(false);
            return;
        }
        boolean ok = resultCode == RESULT_OK && data != null;
        if (ok) {
            ScreenCaptureHelper.storePermissionResult(resultCode, data);
        }
        finishWith(ok);
    }

    private void finishWith(boolean granted) {
        for (PermissionListener l : PENDING) {
            try {
                l.onResult(granted);
            } catch (Exception ignored) {}
        }
        PENDING.clear();
        finish();
    }
}
