package com.pegasuscorp.orbe.pcshell;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Actions ntfy « Non » / « Plus tard » — activité transparente sans UI.
 */
public final class PcShellNtfyActionActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
        finish();
    }

    private void handle(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null) return;
        String action = data.getQueryParameter("action");
        if ("snooze".equalsIgnoreCase(action)) {
            PcShellNtfySnoozeScheduler.scheduleFromDeepLink(this, data);
            int hours = 4;
            try {
                String h = data.getQueryParameter("hours");
                if (h != null && !h.isEmpty()) hours = Integer.parseInt(h);
            } catch (NumberFormatException ignored) {
            }
            Toast.makeText(this, "Rappel mises à jour dans " + hours + " h", Toast.LENGTH_SHORT).show();
        }
        // action=dismiss (« Non ») : rien — ntfy efface déjà la notification (clear:true).
    }
}
