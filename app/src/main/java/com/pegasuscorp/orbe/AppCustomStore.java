package com.pegasuscorp.orbe;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Personnalisation par app dans le tiroir : nom affiché et icône (fichier PNG local).
 * N'altère jamais l'app elle-même — seulement ce qu'Orbe affiche.
 */
public final class AppCustomStore {

    private static final String TAG = "AppCustomStore";
    private static final String PREFS = "app_custom";
    private static final String KEY_VERSION = "version";
    static final int ICON_PX = 192;

    private AppCustomStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Change à chaque modification : à mettre dans les clés de cache d'icônes. */
    public static int version(Context ctx) {
        return prefs(ctx).getInt(KEY_VERSION, 0);
    }

    private static void bump(Context ctx) {
        SharedPreferences p = prefs(ctx);
        p.edit().putInt(KEY_VERSION, p.getInt(KEY_VERSION, 0) + 1).apply();
    }

    /** Nom personnalisé, ou null. */
    public static String label(Context ctx, String pkg) {
        if (ctx == null || pkg == null) return null;
        return prefs(ctx).getString("label:" + pkg, null);
    }

    public static void setLabel(Context ctx, String pkg, String label) {
        SharedPreferences.Editor ed = prefs(ctx).edit();
        if (label == null || label.trim().isEmpty()) ed.remove("label:" + pkg);
        else ed.putString("label:" + pkg, label.trim());
        ed.apply();
        bump(ctx);
    }

    static File iconFile(Context ctx, String pkg) {
        return new File(new File(ctx.getFilesDir(), "app_icons"), pkg + ".png");
    }

    public static boolean hasIcon(Context ctx, String pkg) {
        return ctx != null && pkg != null && iconFile(ctx, pkg).isFile();
    }

    /** Icône personnalisée, ou null. */
    public static Drawable icon(Context ctx, String pkg) {
        if (!hasIcon(ctx, pkg)) return null;
        Bitmap bmp = BitmapFactory.decodeFile(iconFile(ctx, pkg).getAbsolutePath());
        return bmp != null ? new BitmapDrawable(ctx.getResources(), bmp) : null;
    }

    /** Enregistre une image (recadrée au centre, carrée). */
    public static boolean saveIcon(Context ctx, String pkg, Bitmap src) {
        if (src == null) return false;
        int side = Math.min(src.getWidth(), src.getHeight());
        Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2,
                (src.getHeight() - side) / 2, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(square, ICON_PX, ICON_PX, true);
        File f = iconFile(ctx, pkg);
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
        try (FileOutputStream out = new FileOutputStream(f)) {
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            Log.w(TAG, "saveIcon " + pkg, e);
            return false;
        }
        bump(ctx);
        return true;
    }

    public static boolean saveIcon(Context ctx, String pkg, Drawable d) {
        if (d == null) return false;
        Bitmap bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        copy.setBounds(0, 0, ICON_PX, ICON_PX);
        copy.draw(c);
        return saveIcon(ctx, pkg, bmp);
    }

    public static void clearIcon(Context ctx, String pkg) {
        File f = iconFile(ctx, pkg);
        if (f.isFile() && !f.delete()) Log.w(TAG, "delete " + f);
        bump(ctx);
    }

    /** Nom à afficher : personnalisé sinon celui d'Android. */
    public static String displayLabel(Context ctx, String pkg, String systemLabel) {
        String custom = label(ctx, pkg);
        return !TextUtils.isEmpty(custom) ? custom : systemLabel;
    }
}
