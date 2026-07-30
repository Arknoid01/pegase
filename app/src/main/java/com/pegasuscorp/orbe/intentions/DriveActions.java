package com.pegasuscorp.orbe.intentions;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.view.KeyEvent;
import android.widget.Toast;

/**
 * Actions concrètes mode conduite (Spotify + navigation).
 */
public final class DriveActions {

    private DriveActions() {}

    public static void applyDriveMode(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        PegaseModeStore.setMode(app, PegaseModeStore.Mode.DRIVE);

        boolean music = resumeSpotify(app);
        String dest = IntentionPrefs.getDriveDestination(app);
        boolean nav = false;
        if (dest != null && !dest.trim().isEmpty()) {
            nav = openNavigation(app, dest.trim());
        }

        StringBuilder msg = new StringBuilder("Mode conduite");
        if (music) msg.append(" · Spotify");
        if (nav) msg.append(" · nav ").append(dest.trim());
        if (!music && !nav) {
            msg.append(" — configure destination / Spotify dans Routines");
        }
        Toast.makeText(app, msg.toString(), Toast.LENGTH_LONG).show();
    }

    static boolean resumeSpotify(Context app) {
        String query = IntentionPrefs.getDriveSpotifyQuery(app);
        try {
            if (query != null && !query.trim().isEmpty()) {
                Uri uri = Uri.parse("spotify:search:" + Uri.encode(query.trim()));
                Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (intent.resolveActivity(app.getPackageManager()) != null) {
                    app.startActivity(intent);
                    return true;
                }
            }
            // Reprise lecture média
            Intent spotify = app.getPackageManager()
                    .getLaunchIntentForPackage("com.spotify.music");
            if (spotify != null) {
                spotify.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(spotify);
            }
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean openNavigation(Context app, String destination) {
        try {
            String encoded = Uri.encode(destination);
            Intent nav = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("google.navigation:q=" + encoded))
                    .setPackage("com.google.android.apps.maps")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (nav.resolveActivity(app.getPackageManager()) != null) {
                app.startActivity(nav);
                return true;
            }
            Intent waze = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("waze://?q=" + encoded + "&navigate=yes"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (waze.resolveActivity(app.getPackageManager()) != null) {
                app.startActivity(waze);
                return true;
            }
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://www.google.com/maps/dir/?api=1&destination=" + encoded))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(web);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
