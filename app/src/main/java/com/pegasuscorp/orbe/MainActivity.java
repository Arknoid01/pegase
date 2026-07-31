package com.pegasuscorp.orbe;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.mlkit.vision.digitalink.Ink;

import com.pegasuscorp.orbe.bureau.BureauActivity;
import com.pegasuscorp.orbe.chat.ChatVoiceBridge;
import com.pegasuscorp.orbe.chat.ConversationManager;
import com.pegasuscorp.orbe.contextstore.ContextEditor;
import com.pegasuscorp.orbe.conversation.ResponseDelivery;
import com.pegasuscorp.orbe.diag.CorrectionsEditor;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.memory.MemoryEditor;
import com.pegasuscorp.orbe.notepad.NotepadEditor;
import com.pegasuscorp.orbe.orion.OrionChatHistory;
import com.pegasuscorp.orbe.orion.OrionSessionArchive;
import com.pegasuscorp.orbe.prefetch.PrefetchScheduler;
import com.pegasuscorp.orbe.session.Channel;
import com.pegasuscorp.orbe.session.LifecycleBridge;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.session.SessionContext;
import com.pegasuscorp.orbe.ui.HomeAssetController;
import com.pegasuscorp.orbe.ui.HomeLauncherActions;
import com.pegasuscorp.orbe.ui.OrbUiController;
import com.pegasuscorp.orbe.voice.IntentParser;
import com.pegasuscorp.orbe.voice.LocalKeywordParser;
import com.pegasuscorp.orbe.voice.PegaseWakeController;
import com.pegasuscorp.orbe.voice.PegaseWakeStore;
import com.pegasuscorp.orbe.voice.SpeechRulesEditor;
import com.pegasuscorp.orbe.voice.VoiceInputHandler;
import com.pegasuscorp.orbe.voice.VoiceManager;
import com.pegasuscorp.orbe.voice.VoiceMuteStore;
import com.pegasuscorp.orbe.voice.VoiceOutputHandler;
import com.pegasuscorp.orbe.voice.VoiceWakeClient;
import com.pegasuscorp.orbe.voice.WakeHealthUi;

import java.util.concurrent.ExecutorService;

/**
 * Ecran d'accueil du launcher.
 * Voix / orbe / lifecycle → VoiceInputHandler, VoiceOutputHandler,
 * OrbUiController, LifecycleBridge.
 * Assets / imports → HomeAssetController ; lancer apps → HomeLauncherActions.
 */
public class MainActivity extends AppCompatActivity
        implements VoiceInputHandler.VoiceInputCallback,
        LifecycleBridge.Host,
        HomeAssetController.Host,
        HomeLauncherActions.Host {

    private static final long BACK_LONG_PRESS_MS = 450;

    private long backDownTime;

    private HomeRootLayout homeRoot;
    private AppDrawerPanel drawerPanel;
    private HomeWallpaperView homeWallpaper;
    private FluidBackgroundView fluidBackground;
    private HomeVeilView homeVeil;
    private InkDrawingView inkZone;
    private OrbView orbView;
    private TextView gestureHintView;
    private ChargingThreadView chargingThread;
    private ChargingMonitor chargingMonitor;
    private DigitalInkManager inkManager;
    private TextView inkStatusView;

    private VoiceManager voiceManager;
    private VoiceInputHandler voiceInput;
    private VoiceOutputHandler voiceOutput;
    private OrbUiController orbUi;
    private LifecycleBridge lifecycle;
    private HomeAssetController homeAssets;
    private HomeLauncherActions homeLaunchers;

    private PegaseSession pegaseSession;
    private ConversationManager conversation;

    private final ResponseDelivery responseDelivery = new ResponseDelivery();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Trace.init(this);
        try { PrefetchScheduler.ensureScheduled(this); } catch (Exception ignored) {}
        try {
            com.pegasuscorp.orbe.intentions.IntentionScheduler.ensureScheduled(this);
            if (com.pegasuscorp.orbe.intentions.IntentionPrefs.isEnabled(this)) {
                com.pegasuscorp.orbe.permissions.PermissionFlow.ensureNotifications(this);
            }
        } catch (Exception ignored) {}
        try {
            com.pegasuscorp.orbe.f1companion.F1NewsScheduler.ensureScheduled(this);
        } catch (Exception ignored) {}
        try {
            com.pegasuscorp.orbe.f1companion.F1LiveScheduler.ensureScheduled(this);
        } catch (Exception ignored) {}
        PegaseWakeStore.applyStartupSafety(this);
        OrionChatHistory.attachContext(this);
        OrionSessionArchive.purgeOlderThanAsync(this, 7);

        homeLaunchers = new HomeLauncherActions(this);
        homeAssets = new HomeAssetController(this);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        homeRoot = new HomeRootLayout(this);
        FrameLayout root = homeRoot;

        fluidBackground = new FluidBackgroundView(this);
        root.addView(fluidBackground, matchParent());

        homeWallpaper = new HomeWallpaperView(this);
        homeWallpaper.setVisibility(android.view.View.GONE);
        root.addView(homeWallpaper, matchParent());

        homeVeil = new HomeVeilView(this);
        root.addView(homeVeil, matchParent());

        chargingThread = new ChargingThreadView(this);
        chargingThread.setVisibility(android.view.View.GONE);
        root.addView(chargingThread, matchParent());

        orbView = new OrbView(this);
        root.addView(orbView, matchParent());

        gestureHintView = new TextView(this);
        gestureHintView.setTextColor(Color.parseColor("#B8FBF6"));
        gestureHintView.setTextSize(12);
        gestureHintView.setTypeface(android.graphics.Typeface.create("sans-serif-light",
                android.graphics.Typeface.NORMAL));
        gestureHintView.setGravity(Gravity.CENTER_HORIZONTAL);
        gestureHintView.setAlpha(0f);
        gestureHintView.setVisibility(android.view.View.GONE);
        gestureHintView.setPadding(dpHint(24), dpHint(4), dpHint(24), dpHint(4));
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        hintLp.bottomMargin = (int) (dm.heightPixels * 0.055f);
        root.addView(gestureHintView, hintLp);

        inkZone = new InkDrawingView(this, null);
        inkZone.setAlwaysActive(true);
        root.addView(inkZone, matchParent());

        drawerPanel = new AppDrawerPanel(this);
        root.addView(drawerPanel, matchParent());
        drawerPanel.setPersonalizationListener(homeAssets);

        inkStatusView = new TextView(this);
        inkStatusView.setTextColor(Color.parseColor("#50FFFFFF"));
        inkStatusView.setTextSize(10);
        inkStatusView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.gravity = Gravity.TOP;
        statusLp.topMargin = (int) (8 * dm.density);
        inkStatusView.setLayoutParams(statusLp);
        root.addView(inkStatusView);

        homeRoot.bind(orbView, inkZone, drawerPanel);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            homeVeil.setPadding(0, bars.top, 0, bars.bottom);
            chargingThread.setPadding(0, bars.top, 0, bars.bottom);
            inkZone.setPadding(0, bars.top, 0, bars.bottom);
            orbView.setPadding(0, bars.top, 0, bars.bottom);
            statusLp.topMargin = bars.top + (int) (8 * dm.density);
            inkStatusView.setLayoutParams(statusLp);
            return windowInsets;
        });

        setContentView(root);
        AppListCache.warmUp(this);
        homeAssets.registerLaunchers(this);
        homeLaunchers.registerLaunchers(this);

        ShortcutStore.seedDefaultsIfNeeded(this);
        ShortcutStore.migrateSpotifyOrbIfNeeded(this);

        orbUi = new OrbUiController(this, orbView, gestureHintView, mainHandler,
                inkZone, chargingThread, drawerPanel, homeWallpaper, fluidBackground, homeVeil);
        orbUi.refreshShortcutSlots();
        wireOrbGestures();
        orbUi.applyPersonalization();
        WakeHealthUi.setListener(status -> {
            if (orbUi != null) orbUi.applyWakeHealth(status);
        });
        VoiceWakeClient.get().refreshWakeHealth();

        inkManager = DigitalInkManager.getInstance();
        attachInkStatusListener();
        inkZone.setCallback(this::recognizeAndOpenDrawer);
        chargingMonitor = new ChargingMonitor(this, chargingThread::setCharging);

        pegaseSession = PegaseSession.get(this);
        pegaseSession.init(new SessionContext(Channel.VOICE, true));
        conversation = pegaseSession.getConversation();

        homeAssets.maybeOfferPiperDownload();
        wireVoiceAndLifecycle();
        PegaseWakeService.sync(this);
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void wireOrbGestures() {
        orbView.setSlotListener(new OrbView.SlotListener() {
            @Override
            public void onSlotClick(int index) {
                String pkg = ShortcutStore.getPackage(MainActivity.this, index);
                if (pkg == null) homeLaunchers.pickAppForSlot(index);
                else {
                    homeLaunchers.launchPackage(pkg);
                    orbView.collapseIfExpanded();
                }
            }

            @Override
            public void onSlotLongPress(int index) {
                homeLaunchers.pickAppForSlot(index);
            }
        });
        orbView.setOnLongPress(() -> { if (voiceInput != null) voiceInput.enterChatMode(); });
        orbView.setOnDoubleTap(this::openBureau);
        orbView.setOnSwipeUp(this::openAppDrawer);
        orbView.setOnTripleTap(this::openWidgetBoard);
        orbView.setOnPhaseClick(this::openTextDiscussion);
    }

    private void wireVoiceAndLifecycle() {
        voiceManager = ChatVoiceBridge.getSharedVoice(this);
        voiceOutput = new VoiceOutputHandler(this, voiceManager, responseDelivery);
        voiceInput = new VoiceInputHandler(this, voiceManager, voiceOutput, orbUi, this);
        voiceInput.bind(pegaseSession, conversation,
                new LocalKeywordParser(this),
                new MemoryEditor(this),
                new NotepadEditor(this),
                new CorrectionsEditor(this),
                new ContextEditor(this),
                new SpeechRulesEditor(this));
        lifecycle = new LifecycleBridge(this, mainHandler);
        voiceInput.attachVoiceHost();
        VoiceMuteStore.syncController(this);
        PegaseInterfaceState.setOnCloseListener(() -> {
            if (voiceInput != null) voiceInput.resumeChatListeningIfNeeded();
        });
        ChatVoiceBridge.register(this);
        voiceInput.handleWakeIntent(getIntent());
        voiceInput.deliverPendingTranscriptIfAny(getIntent());
    }

    // —— ChatVoiceBridge thin forwards ——

    public void handleVoiceTranscript(String transcript) {
        if (voiceInput != null) voiceInput.handleVoiceTranscript(transcript);
    }

    public void pauseVoiceForInterface() {
        if (voiceInput != null) voiceInput.pauseVoiceForInterface();
    }

    public void resumeVoiceAfterInterface() {
        if (voiceInput != null) voiceInput.resumeVoiceAfterInterface();
    }

    public boolean isChatActiveForBridge() {
        return voiceInput != null && voiceInput.isChatActiveForBridge();
    }

    public void onChatTranscript(String transcript) {
        if (voiceInput != null) voiceInput.onChatTranscript(transcript);
    }

    public void recordToolReplyFromBridge(String reply) {
        if (voiceInput != null) voiceInput.recordToolReplyFromBridge(reply);
    }

    // —— VoiceInputCallback ——

    @Override
    public boolean isActivityAlive() {
        return !isFinishing() && !isDestroyed();
    }

    @Override
    public void showToast(String message, int length) {
        Toast.makeText(this, message, length).show();
    }

    @Override
    public boolean ensureMic() {
        return lifecycle != null && lifecycle.ensureMic();
    }

    @Override
    public void applyLockScreenUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    }

    @Override
    public void executeLauncherCommand(IntentParser.Command cmd, String rawText) {
        executeCommand(cmd, rawText);
    }

    @Override
    public ExecutorService importExecutor() {
        return homeAssets.executor();
    }

    @Override
    public void cancelLlmIdleUnload() {
        if (lifecycle != null) lifecycle.cancelLlmIdleUnload();
    }

    // —— Host (Lifecycle / Assets / Launchers) ——

    @Override
    public AppCompatActivity activity() {
        return this;
    }

    @Override
    public OrbUiController orbUi() {
        return orbUi;
    }

    @Override
    public VoiceOutputHandler voiceOutput() {
        return voiceOutput;
    }

    @Override
    public AppDrawerPanel drawerPanel() {
        return drawerPanel;
    }

    @Override
    public VoiceInputHandler voiceInput() {
        return voiceInput;
    }

    @Override
    public PegaseSession pegaseSession() {
        return pegaseSession;
    }

    @Override
    public void setConversation(ConversationManager conversation) {
        this.conversation = conversation;
    }

    @Override
    public HomeLauncherActions homeLaunchers() {
        return homeLaunchers;
    }

    @Override
    public VoiceManager voiceManager() {
        return voiceManager;
    }

    @Override
    public void resetHomeTouch() {
        if (homeRoot != null) homeRoot.resetTouch();
    }

    @Override
    public void clearInk() {
        if (inkZone != null) inkZone.clear();
    }

    @Override
    public void startChargingMonitor() {
        if (chargingMonitor != null) chargingMonitor.start();
    }

    @Override
    public void stopChargingMonitor() {
        if (chargingMonitor != null) chargingMonitor.stop();
    }

    @Override
    public void onMicGrantedEnterChat() {
        if (voiceInput != null) voiceInput.enterChatMode();
    }

    @Override
    public void onMicGrantedStartListening() {
        if (voiceManager != null) voiceManager.startListening();
    }

    private void recognizeAndOpenDrawer(Ink ink) {
        inkManager.recognize(ink, new DigitalInkManager.RecognitionListener() {
            @Override
            public void onRecognized(String text) {
                if (text == null || text.isEmpty()) return;
                openAppDrawerFiltered(text.substring(0, 1).toLowerCase());
            }

            @Override
            public void onRecognitionFailed(String reason) {
                Toast.makeText(MainActivity.this, reason, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void executeCommand(IntentParser.Command cmd, String rawText) {
        switch (cmd.action) {
            case OPEN_APP:         homeLaunchers.launchAppByLabel(cmd.argument); break;
            case CALL:             homeLaunchers.launchDialer(); break;
            case TIMER:            homeLaunchers.startTimer(cmd.number > 0 ? cmd.number : 5 * 60); break;
            case OPEN_DRAWER:      openAppDrawer(); break;
            case OPEN_NOTEPAD:
                PegaseInterfaceState.openNotepad(this);
                Toast.makeText(this, "Bloc-notes ouvert", Toast.LENGTH_SHORT).show();
                break;
            case OPEN_INTERFACE:
                PegaseInterfaceState.openOrBringToFront(this);
                Toast.makeText(this, "Interface ouverte", Toast.LENGTH_SHORT).show();
                break;
            case OPEN_BUREAU:
                openBureau();
                break;
            case OPEN_API_SETTINGS:
                startActivity(new Intent(this, ApiSettingsActivity.class));
                Toast.makeText(this, "Réglages API", Toast.LENGTH_SHORT).show();
                break;
            case UNKNOWN:
            default:
                if (voiceInput != null) voiceInput.speakUnknownCommand(rawText);
                break;
        }
    }

    @Override
    public void openBureau() {
        if (voiceInput != null && voiceInput.isConversationActive()) {
            Toast.makeText(this, "Termine la discussion avant le bureau", Toast.LENGTH_SHORT).show();
            return;
        }
        prepareHomeTransition();
        PegaseWakeController.setBureauActive(true);
        PegaseWakeController.pauseWake(this);
        if (voiceInput != null) voiceInput.stopListeningForNavigation();
        BureauActivity.open(this);
    }

    private void openTextDiscussion() {
        GestureHintsStore.markDiscovered(this, GestureHintsStore.HINT_DISCUSSION);
        if (orbUi != null) orbUi.hideGestureHintNow();
        prepareHomeTransition();
        if (orbView != null) orbView.collapseIfExpanded();
        if (voiceInput != null) voiceInput.ensureHeavyNativesReady();
        PegaseWakeController.setTextDiscussionActive(true);
        PegaseWakeController.pauseWake(this);
        if (voiceInput != null) voiceInput.stopListeningForNavigation();
        PegaseInterfaceState.openOrBringToFront(this, PegaseInterfaceState.TAB_CONVERSATION);
    }

    private void prepareHomeTransition() {
        if (homeRoot != null) homeRoot.resetTouch();
        if (inkZone != null) inkZone.clear();
    }

    @Override
    public void openAppDrawer() {
        openAppDrawerFiltered(null);
    }

    private void openAppDrawerFiltered(String letter) {
        GestureHintsStore.markDiscovered(this, GestureHintsStore.HINT_APPS);
        if (orbUi != null) orbUi.hideGestureHintNow();
        prepareHomeTransition();
        if (letter != null) drawerPanel.show(letter);
        else drawerPanel.show();
    }

    private int dpHint(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void openWidgetBoard() {
        prepareHomeTransition();
        orbView.collapseIfExpanded();
        startActivity(new Intent(this, WidgetBoardActivity.class));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && drawerPanel != null && drawerPanel.isOpen()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) backDownTime = System.currentTimeMillis();
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (System.currentTimeMillis() - backDownTime >= BACK_LONG_PRESS_MS) {
                    drawerPanel.resetToAllApps();
                } else {
                    returnToHomeScreen();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void returnToHomeScreen() {
        if (drawerPanel != null && drawerPanel.isOpen()) drawerPanel.closeToHome();
        if (orbView != null) orbView.collapseIfExpanded();
    }

    @Override
    public void onBackPressed() {
        if (drawerPanel != null && drawerPanel.isOpen()) {
            returnToHomeScreen();
            return;
        }
        if (voiceInput != null && voiceInput.isConversationActive()) {
            voiceInput.exitChatMode();
            return;
        }
        if (orbView != null && orbView.collapseIfExpanded()) return;
    }

    @Override
    public void attachInkStatusListener() {
        if (inkManager == null) return;
        inkManager.setModelStateListener((state, message) -> runOnUiThread(() -> {
            if (state == DigitalInkManager.ModelState.READY) {
                inkStatusView.setText("");
            } else {
                inkStatusView.setText(message);
                inkStatusView.setTextColor(state == DigitalInkManager.ModelState.ERROR
                        ? Color.parseColor("#80FF6B6B")
                        : Color.parseColor("#50FFFFFF"));
            }
        }));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("wake_activate", false)) {
            if (voiceInput != null) voiceInput.handleWakeIntent(intent);
            return;
        }
        if (intent != null && intent.getBooleanExtra("resume_chat", false)
                && voiceInput != null && voiceInput.isConversationActive()) {
            mainHandler.postDelayed(() -> {
                if (voiceInput != null) voiceInput.resumeChatListeningIfNeeded();
            }, 400);
        }
        if (voiceInput != null) voiceInput.deliverPendingTranscriptIfAny(intent);
    }

    @Override protected void onStart() { super.onStart(); if (lifecycle != null) lifecycle.onStart(); }
    @Override protected void onResume() { super.onResume(); if (lifecycle != null) lifecycle.onResume(); }
    @Override protected void onPause() { if (lifecycle != null) lifecycle.onPause(); super.onPause(); }
    @Override protected void onStop() { if (lifecycle != null) lifecycle.onStop(); super.onStop(); }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (lifecycle != null) lifecycle.onTrimMemory(level);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (lifecycle != null) {
            lifecycle.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    protected void onDestroy() {
        if (lifecycle != null) lifecycle.onDestroy();
        if (homeAssets != null) homeAssets.shutdown();
        super.onDestroy();
    }
}
