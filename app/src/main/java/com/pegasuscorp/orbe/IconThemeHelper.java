package com.pegasuscorp.orbe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * Applique pack d'icônes + style visuel aux icônes d'applications.
 * Ne mute jamais les drawables du cache partagé.
 */
public final class IconThemeHelper {

    private IconThemeHelper() {}

    public static Drawable resolve(Context context, AppListCache.AppEntry entry, int sizePx) {
        Drawable base = safeCopy(entry.icon(context));
        String pack = PersonalizationStore.getIconPack(context);
        if (pack != null && !pack.isEmpty()) {
            Drawable fromPack = IconPackManager.loadIcon(
                    context, pack, entry.pkg, entry.activity, base);
            if (fromPack != null) base = safeCopy(fromPack);
        }
        return apply(context, base, sizePx, PersonalizationStore.getIconTheme(context));
    }

    public static Drawable resolveForPackage(Context context, String pkg,
                                             Drawable systemIcon, int sizePx) {
        Drawable base = safeCopy(systemIcon);
        String pack = PersonalizationStore.getIconPack(context);
        if (pack != null && !pack.isEmpty()) {
            Drawable fromPack = IconPackManager.loadIconForPackage(context, pack, pkg, base);
            if (fromPack != null) base = safeCopy(fromPack);
        }
        return apply(context, base, sizePx, PersonalizationStore.getIconTheme(context));
    }

    public static Drawable apply(Context context, Drawable source, int sizePx, int theme) {
        Drawable drawable = safeCopy(source);
        if (drawable == null || theme == PersonalizationStore.ICON_SYSTEM) {
            return drawable;
        }

        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Path clip = new Path();
        if (theme == PersonalizationStore.ICON_ROUND) {
            clip.addCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, Path.Direction.CW);
        } else {
            float r = sizePx * 0.22f;
            clip.addRoundRect(0, 0, sizePx, sizePx, r, r, Path.Direction.CW);
        }
        canvas.clipPath(clip);
        drawable.setBounds(0, 0, sizePx, sizePx);
        drawable.draw(canvas);

        return new BitmapDrawable(context.getResources(), bitmap);
    }

    private static Drawable safeCopy(Drawable source) {
        if (source == null) return null;
        Drawable.ConstantState state = source.getConstantState();
        if (state != null) return state.newDrawable().mutate();
        return source.mutate();
    }
}
