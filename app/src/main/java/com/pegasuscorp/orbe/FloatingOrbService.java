package com.pegasuscorp.orbe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.pegasuscorp.orbe.copilot.CopilotBubblePanel;
import com.pegasuscorp.orbe.copilot.CopilotController;
import com.pegasuscorp.orbe.copilot.CopilotPrefs;

/**
 * Orbe flottante par-dessus toutes les apps.
 *
 * Modes :
 *  - {@link OverlayMode#VOICE} : discussion vocale en cours (tap → MainActivity).
 *  - {@link OverlayMode#COPILOT} : copilote permanent, bulle messenger, capture écran.
 *
 * Prérequis : permission SYSTEM_ALERT_WINDOW.
 */
public class FloatingOrbService extends Service {

    public enum OverlayMode {
        VOICE,
        COPILOT
    }

    private static final String CHANNEL_ID = "pegase_overlay";
    private static final int NOTIF_ID = 42;
    private static final int VOICE_ORB_PX = 160;
    private static final int COPILOT_ORB_PX = 56;
    private static final int BUBBLE_W_DP = 300;
    private static final int BUBBLE_H_DP = 380;

    private static volatile boolean running;
    private static volatile OverlayMode currentMode = OverlayMode.VOICE;

    private WindowManager wm;
    private FrameLayout overlayRoot;
    private View orbView;
    private CopilotBubblePanel bubblePanel;
    private WindowManager.LayoutParams layoutParams;
    private CopilotController copilotController;
    private boolean bubbleExpanded;
    private boolean foregroundStarted;
    private float density;

    @Override
    public void onCreate() {
        super.onCreate();
        density = getResources().getDisplayMetrics().density;
        createNotificationChannel();
        if (!startAsForeground()) {
            stopSelf();
            return;
        }
        showOverlay();
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foregroundStarted) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.hasExtra("mode")) {
            String mode = intent.getStringExtra("mode");
            if ("copilot".equals(mode)) {
                currentMode = OverlayMode.COPILOT;
            } else if ("voice".equals(mode)) {
                currentMode = OverlayMode.VOICE;
            }
        }
        if (overlayRoot == null) showOverlay();
        else applyModeLayout();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        detachCopilot();
        removeOverlay();
        foregroundStarted = false;
        running = false;
        super.onDestroy();
    }

    private boolean startAsForeground() {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, notification);
            }
            foregroundStarted = true;
            return true;
        } catch (SecurityException e) {
            android.util.Log.e("FloatingOrb",
                    "Permission FGS manquante — orbe flottante désactivée", e);
        } catch (RuntimeException e) {
            android.util.Log.e("FloatingOrb", "Impossible de démarrer en premier plan", e);
        }
        foregroundStarted = false;
        running = false;
        return false;
    }

    private void showOverlay() {
        if (overlayRoot != null) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;

        overlayRoot = new FrameLayout(this);
        overlayRoot.setBackgroundColor(Color.TRANSPARENT);

        orbView = new MiniOrbView(this, currentMode == OverlayMode.COPILOT);
        overlayRoot.addView(orbView, new FrameLayout.LayoutParams(
                orbSizePx(), orbSizePx(), Gravity.END | Gravity.BOTTOM));

        if (currentMode == OverlayMode.COPILOT) {
            setupCopilotBubble();
            bubbleExpanded = CopilotPrefs.isBubbleOpen(this);
            if (bubbleExpanded) showBubble();
        }

        layoutParams = buildLayoutParams();
        int savedX = CopilotPrefs.getOrbX(this);
        int savedY = CopilotPrefs.getOrbY(this);
        if (savedX >= 0 && savedY >= 0) {
            layoutParams.x = savedX;
            layoutParams.y = savedY;
        } else {
            layoutParams.x = dp(12);
            layoutParams.y = dp(120);
        }

        attachOrbTouchListener();

        try {
            wm.addView(overlayRoot, layoutParams);
            if (currentMode == OverlayMode.COPILOT) attachCopilot();
        } catch (Exception e) {
            android.util.Log.e("FloatingOrb", "Overlay impossible", e);
            overlayRoot = null;
            stopSelf();
        }
    }

    private void setupCopilotBubble() {
        bubblePanel = new CopilotBubblePanel(this);
        bubblePanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams bubbleLp = new FrameLayout.LayoutParams(
                dp(BUBBLE_W_DP), dp(BUBBLE_H_DP), Gravity.END | Gravity.BOTTOM);
        bubbleLp.bottomMargin = orbSizePx() + dp(8);
        overlayRoot.addView(bubblePanel, bubbleLp);
        bubblePanel.setListener(new CopilotBubblePanel.Listener() {
            @Override
            public void onSend(String text) {
                if (copilotController != null) copilotController.sendUserMessage(text);
            }

            @Override
            public void onCaptureScreen() {
                if (copilotController != null) {
                    copilotController.captureAndAnalyze(null);
                }
            }

            @Override
            public void onRememberScreen() {
                if (copilotController != null) copilotController.rememberFromScreen();
            }

            @Override
            public void onClose() {
                toggleBubble(false);
            }

            @Override
            public void onOpenPegase() {
                PegaseInterfaceActivity.open(FloatingOrbService.this);
            }
        });
    }

    private void attachCopilot() {
        copilotController = CopilotController.get(this);
        copilotController.attach(new CopilotController.BubbleSink() {
            @Override
            public void onUserMessage(String text) {
                if (bubblePanel != null) bubblePanel.addUserMessage(text);
            }

            @Override
            public void onAssistantMessage(String text) {
                if (bubblePanel != null) bubblePanel.addAssistantMessage(text);
            }

            @Override
            public void onAssistantPartial(String text) {
                if (bubblePanel != null) bubblePanel.updateAssistantPartial(text);
            }

            @Override
            public void onStatus(String status) {
                if (bubblePanel != null) bubblePanel.setStatus(status);
            }

            @Override
            public void onError(String message) {
                if (bubblePanel != null) bubblePanel.showError(message);
            }

            @Override
            public void onSendingChanged(boolean sending) {
                if (bubblePanel != null) bubblePanel.setSending(sending);
            }

            @Override
            public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                if (bubblePanel != null) {
                    bubblePanel.showConfirm(question, onConfirm, onCancel);
                }
            }
        });
    }

    private void detachCopilot() {
        if (copilotController != null) {
            copilotController.detach();
            copilotController = null;
        }
    }

    private void attachOrbTouchListener() {
        orbView.setOnTouchListener(new DragAndTapListener(wm, layoutParams, overlayRoot, () -> {
            if (currentMode == OverlayMode.COPILOT) {
                toggleBubble(!bubbleExpanded);
            } else {
                Intent i = new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        .putExtra("resume_chat", true);
                startActivity(i);
            }
        }, () -> {
            if (currentMode == OverlayMode.COPILOT) {
                showCopilotMenu();
            }
        }));
    }

    private void toggleBubble(boolean open) {
        bubbleExpanded = open;
        CopilotPrefs.setBubbleOpen(this, open);
        if (open) showBubble();
        else hideBubble();
        updateWindowFocus();
    }

    private void showBubble() {
        if (bubblePanel == null) return;
        bubblePanel.setVisibility(View.VISIBLE);
        applyExpandedWindowSize();
    }

    private void hideBubble() {
        if (bubblePanel == null) return;
        bubblePanel.setVisibility(View.GONE);
        applyCollapsedWindowSize();
    }

    private void applyModeLayout() {
        if (overlayRoot == null || orbView == null) return;
        FrameLayout.LayoutParams orbLp = (FrameLayout.LayoutParams) orbView.getLayoutParams();
        int size = orbSizePx();
        orbLp.width = size;
        orbLp.height = size;
        orbView.requestLayout();
        if (currentMode == OverlayMode.COPILOT) {
            if (bubblePanel == null) setupCopilotBubble();
            attachCopilot();
            if (bubbleExpanded) showBubble();
            else hideBubble();
        } else {
            detachCopilot();
            if (bubblePanel != null) bubblePanel.setVisibility(View.GONE);
            applyCollapsedWindowSize();
        }
        updateWindowFocus();
    }

    private void applyExpandedWindowSize() {
        if (layoutParams == null || wm == null) return;
        layoutParams.width = dp(BUBBLE_W_DP);
        layoutParams.height = dp(BUBBLE_H_DP) + orbSizePx() + dp(16);
        wm.updateViewLayout(overlayRoot, layoutParams);
    }

    private void applyCollapsedWindowSize() {
        if (layoutParams == null || wm == null) return;
        layoutParams.width = orbSizePx();
        layoutParams.height = orbSizePx();
        wm.updateViewLayout(overlayRoot, layoutParams);
    }

    private void updateWindowFocus() {
        if (layoutParams == null || wm == null) return;
        if (currentMode == OverlayMode.COPILOT && bubbleExpanded) {
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        } else {
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        }
        wm.updateViewLayout(overlayRoot, layoutParams);
    }

    private void showCopilotMenu() {
        PopupMenu menu = new PopupMenu(this, orbView);
        menu.getMenu().add(0, 1, 0, bubbleExpanded ? "Fermer la bulle" : "Ouvrir la bulle");
        menu.getMenu().add(0, 2, 0, "Ouvrir Pégase");
        menu.getMenu().add(0, 3, 0,
                CopilotPrefs.isAlwaysOn(this) ? "Désactiver l'orbe" : "Activer l'orbe");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    toggleBubble(!bubbleExpanded);
                    return true;
                case 2:
                    PegaseInterfaceActivity.open(this);
                    return true;
                case 3:
                    boolean on = !CopilotPrefs.isAlwaysOn(this);
                    CopilotPrefs.setAlwaysOn(this, on);
                    if (!on) hide(this);
                    else Toast.makeText(this, "Orbe copilote activée", Toast.LENGTH_SHORT).show();
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private WindowManager.LayoutParams buildLayoutParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int w = currentMode == OverlayMode.COPILOT && bubbleExpanded
                ? dp(BUBBLE_W_DP) : orbSizePx();
        int h = currentMode == OverlayMode.COPILOT && bubbleExpanded
                ? dp(BUBBLE_H_DP) + orbSizePx() + dp(16) : orbSizePx();
        return new WindowManager.LayoutParams(
                w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
    }

    private int orbSizePx() {
        return currentMode == OverlayMode.COPILOT ? dp(COPILOT_ORB_PX) : VOICE_ORB_PX;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private void removeOverlay() {
        if (wm != null && overlayRoot != null) {
            try { wm.removeView(overlayRoot); } catch (Exception ignored) {}
            overlayRoot = null;
            orbView = null;
            bubblePanel = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Pégase copilote", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Orbe flottante Pégase");
            ch.setSound(null, null);
            ch.enableVibration(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = currentMode == OverlayMode.COPILOT
                ? new Intent(this, PegaseInterfaceActivity.class)
                : new Intent(this, MainActivity.class)
                        .putExtra("resume_chat", true);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = currentMode == OverlayMode.COPILOT
                ? "Copilote actif — tap l'orbe pour discuter"
                : "Discussion en cours — tap l'orbe pour revenir";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Pégase")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(tap)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /** Orbe pendant discussion vocale (legacy). */
    public static void show(Context ctx) {
        show(ctx, OverlayMode.VOICE);
    }

    public static void showCopilot(Context ctx) {
        if (!CopilotPrefs.isAlwaysOn(ctx)) return;
        if (!android.provider.Settings.canDrawOverlays(ctx)) return;
        show(ctx, OverlayMode.COPILOT);
    }

    private static void show(Context ctx, OverlayMode mode) {
        if (NasaImagePreviewActivity.isShowing()) return;
        currentMode = mode;
        try {
            Intent i = new Intent(ctx, FloatingOrbService.class);
            i.putExtra("mode", mode == OverlayMode.COPILOT ? "copilot" : "voice");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            android.util.Log.w("FloatingOrb", "Démarrage orbe flottante impossible", e);
        }
    }

    public static void hide(Context ctx) {
        if (!running) return;
        ctx.stopService(new Intent(ctx, FloatingOrbService.class));
    }

    public static boolean isRunning() {
        return running;
    }

    public static OverlayMode getMode() {
        return currentMode;
    }

    private static class MiniOrbView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean discreet;

        MiniOrbView(Context ctx, boolean discreet) {
            super(ctx);
            this.discreet = discreet;
            setBackgroundColor(Color.TRANSPARENT);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) * 0.42f;
            int core = discreet ? Color.parseColor("#80D0FFFE") : Color.parseColor("#D0FFFE");
            int mid = discreet ? Color.parseColor("#5035D0DD") : Color.parseColor("#35D0DD");
            int edge = discreet ? Color.parseColor("#300B7D8F") : Color.parseColor("#0B7D8F");
            paint.setShader(new RadialGradient(cx, cy, r,
                    new int[]{core, mid, edge},
                    new float[]{0f, 0.46f, 1f}, Shader.TileMode.CLAMP));
            glowPaint.setColor(discreet ? Color.parseColor("#1835D0DD")
                    : Color.parseColor("#3035D0DD"));
            glowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(
                    r * (discreet ? 0.2f : 0.35f),
                    android.graphics.BlurMaskFilter.Blur.NORMAL));
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) * 0.42f;
            if (!discreet) c.drawCircle(cx, cy, r * 1.3f, glowPaint);
            c.drawCircle(cx, cy, r, paint);
        }
    }

    private static class DragAndTapListener implements View.OnTouchListener {

        private final WindowManager wm;
        private final WindowManager.LayoutParams params;
        private final View root;
        private final Runnable onTap;
        private final Runnable onLongPress;

        private float startX, startY;
        private int startParamX, startParamY;
        private boolean dragged;
        private long downTime;
        private int maxX = Integer.MAX_VALUE;
        private int maxY = Integer.MAX_VALUE;

        DragAndTapListener(WindowManager wm, WindowManager.LayoutParams p, View root,
                           Runnable onTap, Runnable onLongPress) {
            this.wm = wm;
            this.params = p;
            this.root = root;
            this.onTap = onTap;
            this.onLongPress = onLongPress;
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            root.post(() -> {
                maxX = Math.max(0, dm.widthPixels - root.getWidth());
                maxY = Math.max(0, dm.heightPixels - root.getHeight());
            });
        }

        private void clampPosition() {
            params.x = Math.max(0, Math.min(params.x, maxX));
            params.y = Math.max(0, Math.min(params.y, maxY));
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX();
                    startY = e.getRawY();
                    startParamX = params.x;
                    startParamY = params.y;
                    dragged = false;
                    downTime = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - startX, dy = e.getRawY() - startY;
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) dragged = true;
                    if (dragged) {
                        params.x = startParamX + (int) dx;
                        params.y = startParamY + (int) dy;
                        clampPosition();
                        wm.updateViewLayout(root, params);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    Context ctx = v.getContext();
                    clampPosition();
                    wm.updateViewLayout(root, params);
                    CopilotPrefs.setOrbPosition(ctx, params.x, params.y);
                    long elapsed = System.currentTimeMillis() - downTime;
                    if (!dragged) {
                        if (elapsed > 500 && onLongPress != null) onLongPress.run();
                        else onTap.run();
                    }
                    return true;
            }
            return false;
        }
    }
}
