package com.pegasuscorp.orbe;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Aperçu NASA en plein écran (fond semi-transparent) — fiable sur tous les appareils.
 */
public class NasaImagePreviewActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_PATH = "image_path";

    private static final AtomicBoolean SHOWING = new AtomicBoolean(false);

    public static boolean isShowing() {
        return SHOWING.get();
    }

    public static void open(Context ctx, String imagePath) {
        if (ctx == null || imagePath == null || imagePath.isEmpty()) return;
        SHOWING.set(true);
        FloatingOrbService.hide(ctx);
        Intent i = new Intent(ctx, NasaImagePreviewActivity.class);
        i.putExtra(EXTRA_IMAGE_PATH, imagePath);
        // NEW_TASK obligatoire depuis ApplicationContext ; CLEAR_TOP évite les doubles.
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            ctx.startActivity(i);
        } catch (Exception e) {
            SHOWING.set(false);
            android.util.Log.e("NasaPreview", "Impossible d'ouvrir l'aperçu NASA", e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SHOWING.set(true);

        String path = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
        if (path == null) {
            finish();
            return;
        }

        File file = new File(path);
        if (!file.exists()) {
            Toast.makeText(this, "Fichier image introuvable.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Bitmap bmp = decodeScaled(file);
        if (bmp == null) {
            Toast.makeText(this, "Impossible d'afficher la photo NASA.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (14 * density);
        int maxImgH = (int) (getResources().getDisplayMetrics().heightPixels * 0.65f);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#E6000000"));
        root.setOnClickListener(v -> finish());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        card.setClickable(true);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(16 * density);
        cardBg.setColor(Color.parseColor("#FF0B0E14"));
        card.setBackground(cardBg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("NASA — Image du jour");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.parseColor("#AAFFFFFF"));
        close.setTextSize(22);
        close.setPadding((int) (8 * density), 0, 0, 0);
        close.setOnClickListener(v -> finish());
        header.addView(close);
        card.addView(header);

        ImageView image = new ImageView(this);
        image.setImageBitmap(bmp);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setMaxHeight(maxImgH);
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        imgLp.topMargin = (int) (10 * density);
        card.addView(image, imgLp);

        TextView hint = new TextView(this);
        hint.setText("Tap à l'extérieur ou ✕ pour fermer");
        hint.setTextColor(Color.parseColor("#88FFFFFF"));
        hint.setTextSize(11);
        hint.setPadding(0, (int) (8 * density), 0, 0);
        card.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int cardW = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
        FrameLayout.LayoutParams scrollLp = new FrameLayout.LayoutParams(
                cardW, FrameLayout.LayoutParams.WRAP_CONTENT);
        scrollLp.gravity = Gravity.CENTER;
        root.addView(scroll, scrollLp);

        setContentView(root);
    }

    @Override
    protected void onDestroy() {
        SHOWING.set(false);
        super.onDestroy();
    }

    private static Bitmap decodeScaled(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxSide / sample > 2048) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }
}
