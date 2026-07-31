package com.pegasuscorp.orbe.copilot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import com.pegasuscorp.orbe.R;
import com.pegasuscorp.orbe.ui.OrbeTokens;

import java.util.ArrayList;
import java.util.List;

/**
 * Overlay de traduction positionnée sur les bounds du texte original (100% local).
 */
public class TranslationOverlayService extends Service {

    private static final String CHANNEL_ID = "pegase_translation_overlay";
    private static final int NOTIF_ID = 86;
    private static final long AUTO_HIDE_MS = 12_000L;

    private static volatile List<TranslatedBlock> pendingBlocks = new ArrayList<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private FrameLayout root;
    private Runnable hideRunnable;
    private float density;

    public static final class TranslatedBlock {
        public final String original;
        public final String translated;
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public TranslatedBlock(String original, String translated,
                int left, int top, int right, int bottom) {
            this.original = original;
            this.translated = translated;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    public static void show(Context ctx, List<TranslatedBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            hide(ctx);
            return;
        }
        if (!CopilotPrefs.isTranslationOverlayEnabled(ctx)) return;
        startShow(ctx, blocks);
    }

    /** Overlay explication v4 — indépendant du toggle traduction. */
    public static void showExplain(Context ctx, List<TranslatedBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            hide(ctx);
            return;
        }
        startShow(ctx, blocks);
    }

    private static void startShow(Context ctx, List<TranslatedBlock> blocks) {
        pendingBlocks = new ArrayList<>(blocks);
        Intent i = new Intent(ctx, TranslationOverlayService.class);
        i.setAction("show");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) {}
    }

    public static void hide(Context ctx) {
        pendingBlocks = new ArrayList<>();
        ctx.stopService(new Intent(ctx, TranslationOverlayService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        density = getResources().getDisplayMetrics().density;
        createChannel();
        startAsForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        renderBlocks(new ArrayList<>(pendingBlocks));
        scheduleAutoHide();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        removeOverlay();
        if (hideRunnable != null) main.removeCallbacks(hideRunnable);
        super.onDestroy();
    }

    private void renderBlocks(List<TranslatedBlock> blocks) {
        removeOverlay();
        if (blocks.isEmpty()) {
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;

        root = BoundsOverlayHelper.createRoot(this);

        for (TranslatedBlock block : blocks) {
            if (block == null || block.translated == null || block.translated.isEmpty()) continue;
            TextView label = buildLabel(block);
            root.addView(label, BoundsOverlayHelper.childAt(
                    block.left,
                    block.top - dp(4),
                    Math.max(dp(48), block.right - block.left + dp(8)),
                    FrameLayout.LayoutParams.WRAP_CONTENT));
        }

        try {
            BoundsOverlayHelper.addView(wm, root);
        } catch (Exception e) {
            root = null;
            stopSelf();
        }
    }

    private TextView buildLabel(TranslatedBlock block) {
        TextView tv = new TextView(this);
        tv.setText(block.translated);
        tv.setTextColor(OrbeTokens.COLOR_TEXT);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setPadding(dp(6), dp(3), dp(6), dp(3));
        tv.setMaxLines(4);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E0182830"));
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), Color.parseColor("#8035D0DD"));
        tv.setBackground(bg);
        return tv;
    }

    private void scheduleAutoHide() {
        if (hideRunnable != null) main.removeCallbacks(hideRunnable);
        hideRunnable = () -> {
            hideRunnable = null;
            stopSelf();
        };
        main.postDelayed(hideRunnable, AUTO_HIDE_MS);
    }

    private void removeOverlay() {
        BoundsOverlayHelper.removeView(wm, root);
        root = null;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private void startAsForeground() {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.copilot_fgs_translation_title))
                .setContentText(getString(R.string.copilot_fgs_translation_text))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Traduction", NotificationManager.IMPORTANCE_MIN);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
