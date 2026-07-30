package com.pegasuscorp.orbe.bureau;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Export PNG de la feuille bureau. */
public final class BureauExporter {

    private BureauExporter() {}

    public static boolean shareSheet(Context context, Bitmap bitmap) {
        if (context == null || bitmap == null) return false;
        try {
            File dir = new File(context.getCacheDir(), "generated");
            if (!dir.exists()) dir.mkdirs();
            String name = "bureau_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(new Date())
                    + ".png";
            File out = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            Uri uri = FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", out);
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(share, "Partager le bureau")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
