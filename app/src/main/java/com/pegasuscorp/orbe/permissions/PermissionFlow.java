package com.pegasuscorp.orbe.permissions;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Demandes runtime de permissions (calendrier, notifications, etc.).
 */
public final class PermissionFlow {

    public static final int REQ_CALENDAR = 7101;
    public static final int REQ_NOTIFICATIONS = 7102;
    public static final int REQ_LOCATION = 7103;

    private PermissionFlow() {}

    public static boolean hasCalendar(Context ctx) {
        if (ctx == null) return false;
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @return true si déjà accordée ; false si une demande a été lancée (ou impossible).
     */
    public static boolean ensureCalendar(Context ctx) {
        if (hasCalendar(ctx)) return true;
        Activity activity = findActivity(ctx);
        if (activity == null) return false;
        ActivityCompat.requestPermissions(activity,
                new String[]{
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR
                },
                REQ_CALENDAR);
        return false;
    }

    /** Avant API 33 : toujours true. */
    public static boolean hasNotifications(Context ctx) {
        if (ctx == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @return true si déjà accordée (ou API &lt; 33) ; false si une demande a été lancée.
     */
    public static boolean ensureNotifications(Context ctx) {
        if (hasNotifications(ctx)) return true;
        Activity activity = findActivity(ctx);
        if (activity == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQ_NOTIFICATIONS);
        return false;
    }

    public static boolean hasLocation(Context ctx) {
        if (ctx == null) return false;
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @return true si déjà accordée ; false si une demande a été lancée (ou impossible).
     */
    public static boolean ensureLocation(Context ctx) {
        if (hasLocation(ctx)) return true;
        Activity activity = findActivity(ctx);
        if (activity == null) return false;
        ActivityCompat.requestPermissions(activity,
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                },
                REQ_LOCATION);
        return false;
    }

    public static Activity findActivity(Context ctx) {
        Context c = ctx;
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
