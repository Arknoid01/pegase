package com.pegasuscorp.orbe.copilot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.pegasuscorp.orbe.R;
import com.pegasuscorp.orbe.chat.ChatSessionRegistry;
import com.pegasuscorp.orbe.chat.OpenRouterVisionClient;
import com.pegasuscorp.orbe.memory.MemoryEntry;
import com.pegasuscorp.orbe.memory.MemoryRepository;
import com.pegasuscorp.orbe.session.Channel;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.session.SessionContext;
import com.pegasuscorp.orbe.session.SessionObserver;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.voice.PegaseWakeController;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pont entre l'overlay copilote et {@link PegaseSession}.
 * Gère envoi texte, capture écran + vision, et mémorisation contextuelle.
 */
public final class CopilotController implements SessionObserver {

    public interface BubbleSink {
        void onUserMessage(String text);
        void onAssistantMessage(String text);
        void onAssistantPartial(String text);
        void onStatus(String status);
        void onError(String message);
        void onSendingChanged(boolean sending);
        void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel);
    }

    private static volatile CopilotController instance;
    private static final ExecutorService REFLECTION_IO = Executors.newSingleThreadExecutor();

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile BubbleSink bubbleSink;
    private volatile boolean sending;
    private volatile String lastScreenContext = "";
    private BroadcastReceiver notifReceiver;
    private BroadcastReceiver statusReceiver;

    private CopilotController(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    public static CopilotController get(Context ctx) {
        if (instance == null) {
            synchronized (CopilotController.class) {
                if (instance == null) {
                    instance = new CopilotController(ctx);
                }
            }
        }
        return instance;
    }

    public void attach(BubbleSink sink) {
        bubbleSink = sink;
        PegaseSession session = PegaseSession.get(appContext);
        session.addObserver(this);
        session.init(new SessionContext(Channel.COPILOT, false));
        registerNotifReceiver();
        registerStatusReceiver();
    }

    private void registerStatusReceiver() {
        if (statusReceiver != null) return;
        statusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String error = intent.getStringExtra("error");
                if (bubbleSink != null && !TextUtils.isEmpty(error)) {
                    bubbleSink.onError(error);
                }
            }
        };
        IntentFilter filter = new IntentFilter(CopilotStatusBridge.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(statusReceiver, filter);
        }
    }

    private void registerNotifReceiver() {
        if (notifReceiver != null) return;
        notifReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String pkg = intent.getStringExtra("package");
                String appLabel = intent.getStringExtra("appLabel");
                String title = intent.getStringExtra("title");
                String text = intent.getStringExtra("text");
                String msg = CopilotNotificationSummarizer.summarize(pkg, appLabel, title, text);
                if (bubbleSink != null && !TextUtils.isEmpty(msg)) {
                    bubbleSink.onAssistantMessage(msg);
                }
            }
        };
        IntentFilter filter = new IntentFilter(CopilotNotificationBridge.ACTION_IMPORTANT_NOTIF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(notifReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(notifReceiver, filter);
        }
    }

    public void detach() {
        if (statusReceiver != null) {
            try {
                appContext.unregisterReceiver(statusReceiver);
            } catch (Exception ignored) {}
            statusReceiver = null;
        }
        if (notifReceiver != null) {
            try {
                appContext.unregisterReceiver(notifReceiver);
            } catch (Exception ignored) {}
            notifReceiver = null;
        }
        PegaseSession.get(appContext).removeObserver(this);
        bubbleSink = null;
    }

    public boolean isSending() {
        return sending;
    }

    public void sendUserMessage(String text) {
        if (TextUtils.isEmpty(text)) return;
        String trimmed = text.trim();
        BubbleSink sink = bubbleSink;
        if (sink != null) sink.onUserMessage(trimmed);

        Context ctx = appContext;
        if (!ChatSessionRegistry.get(ctx).isActive()) {
            PegaseWakeController.setTextDiscussionActive(true);
            PegaseWakeController.pauseWake(ctx);
        }

        String payload = buildPayload(trimmed, null);
        setSending(true);
        dispatchUserTurn(trimmed, payload);
    }

    private void dispatchUserTurn(String trimmed, String payloadWithoutReflection) {
        Context ctx = appContext;
        if (!CopilotReflectionGate.needsReflection(ctx, trimmed)) {
            PegaseSession.get(ctx).send(payloadWithoutReflection, trimmed, sessionObserver());
            return;
        }

        BubbleSink sink = bubbleSink;
        if (sink != null) {
            sink.onStatus(appContext.getString(R.string.copilot_status_thinking));
        }

        REFLECTION_IO.execute(() -> {
            String reflectionPlan = "";
            try {
                CopilotScreenContext.Snapshot snap = CopilotScreenContext.readFresh(ctx);
                String prompt = CopilotReflectionPlanner.buildReflectionPrompt(snap, trimmed);
                reflectionPlan = PegaseSession.get(ctx).completeCopilotReflectionSync(prompt);
            } catch (Exception e) {
                android.util.Log.w("CopilotReflection", "reflection skipped", e);
            }
            String prefix = CopilotReflectionPlanner.buildPayloadPrefix(reflectionPlan);
            String payload = buildPayload(trimmed, prefix.isEmpty() ? null : prefix);
            PegaseSession.get(ctx).send(payload, trimmed, sessionObserver());
        });
    }

    /** Capture l'écran puis analyse via vision (OpenRouter). */
    public void captureAndAnalyze(String userPrompt) {
        BubbleSink sink = bubbleSink;
        if (sink != null) sink.onStatus(appContext.getString(R.string.copilot_status_capturing));

        ScreenCaptureHelper.capture(appContext, new ScreenCaptureHelper.Callback() {
            @Override
            public void onNeedPermission() {
                main.post(() -> {
                    if (bubbleSink != null) {
                        bubbleSink.onStatus(appContext.getString(R.string.copilot_status_capture_permission));
                    }
                    ScreenCapturePermissionActivity.request(appContext, granted -> {
                        if (!granted) {
                            if (bubbleSink != null) {
                                bubbleSink.onError(appContext.getString(R.string.copilot_error_capture_denied));
                            }
                            return;
                        }
                        captureAndAnalyze(userPrompt);
                    });
                });
            }

            @Override
            public void onCaptured(byte[] jpeg) {
                String prompt = TextUtils.isEmpty(userPrompt)
                        ? appContext.getString(R.string.copilot_vision_default_prompt)
                        : userPrompt.trim();
                if (bubbleSink != null) {
                    bubbleSink.onStatus(appContext.getString(R.string.copilot_status_analyzing));
                }
                OpenRouterVisionClient.analyzeJpegBytes(appContext, jpeg, prompt,
                        new OpenRouterVisionClient.Callback() {
                            @Override
                            public void onSuccess(String analysis) {
                                main.post(() -> {
                                    lastScreenContext = analysis != null ? analysis.trim() : "";
                                    if (bubbleSink != null) {
                                        bubbleSink.onAssistantMessage(lastScreenContext);
                                        bubbleSink.onSendingChanged(false);
                                    }
                                });
                            }

                            @Override
                            public void onError(String message) {
                                main.post(() -> {
                                    if (bubbleSink != null) {
                                        bubbleSink.onError(message);
                                        bubbleSink.onSendingChanged(false);
                                    }
                                });
                            }
                        });
            }

            @Override
            public void onError(String message) {
                main.post(() -> {
                    if (bubbleSink != null) {
                        bubbleSink.onError(message);
                        bubbleSink.onSendingChanged(false);
                    }
                });
            }
        });
    }

    /** Retient le contenu visible (capture + vision → mémoire permanente). */
    public void rememberFromScreen() {
        BubbleSink sink = bubbleSink;
        if (sink != null) sink.onStatus(appContext.getString(R.string.copilot_status_remembering));

        ScreenCaptureHelper.capture(appContext, new ScreenCaptureHelper.Callback() {
            @Override
            public void onNeedPermission() {
                main.post(() -> ScreenCapturePermissionActivity.request(appContext, granted -> {
                    if (!granted) {
                        if (bubbleSink != null) {
                            bubbleSink.onError(appContext.getString(R.string.copilot_error_capture_denied));
                        }
                        return;
                    }
                    rememberFromScreen();
                }));
            }

            @Override
            public void onCaptured(byte[] jpeg) {
                OpenRouterVisionClient.analyzeJpegBytes(appContext, jpeg,
                        "Extrais les informations importantes visibles à l'écran "
                                + "(page web, article, données) en une phrase concise "
                                + "à mémoriser pour l'utilisateur. Pas de markdown.",
                        new OpenRouterVisionClient.Callback() {
                            @Override
                            public void onSuccess(String analysis) {
                                main.post(() -> storeMemory(analysis));
                            }

                            @Override
                            public void onError(String message) {
                                main.post(() -> {
                                    if (bubbleSink != null) bubbleSink.onError(message);
                                });
                            }
                        });
            }

            @Override
            public void onError(String message) {
                main.post(() -> {
                    if (bubbleSink != null) bubbleSink.onError(message);
                });
            }
        });
    }

    private void storeMemory(String analysis) {
        String text = analysis != null ? analysis.trim() : "";
        if (text.isEmpty()) {
            if (bubbleSink != null) {
                bubbleSink.onError(appContext.getString(R.string.copilot_error_nothing_to_remember));
            }
            return;
        }
        MemoryRepository repo = MemoryRepository.getInstance(appContext);
        repo.addPermanentMemory(new MemoryEntry(
                "context", text, 0.9,
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                        .format(new java.util.Date()),
                MemoryEntry.SOURCE_USER));
        lastScreenContext = text;
        if (bubbleSink != null) {
            bubbleSink.onAssistantMessage(
                    appContext.getString(R.string.copilot_remember_prefix, text));
        }
    }

    private String buildPayload(String userText, String reflectionPrefix) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(lastScreenContext)) {
            sb.append("[Capture écran manuelle]\n").append(lastScreenContext).append("\n\n");
        }
        if (!TextUtils.isEmpty(reflectionPrefix)) {
            sb.append(reflectionPrefix);
        }
        if (sb.length() == 0) return userText;
        sb.append("[Question]\n").append(userText);
        return sb.toString();
    }

    private SessionObserver sessionObserver() {
        return new SessionObserver() {
            @Override
            public void onReply(String text, boolean toolFired) {
                main.post(() -> {
                    if (!toolFired && bubbleSink != null && !TextUtils.isEmpty(text)) {
                        bubbleSink.onAssistantMessage(text);
                    }
                    setSending(false);
                });
            }

            @Override
            public void onPartial(String accumulated) {
                main.post(() -> {
                    if (bubbleSink != null) bubbleSink.onAssistantPartial(accumulated);
                });
            }

            @Override
            public void onToolResult(ToolResult result) {
                main.post(() -> {
                    if (bubbleSink != null && result != null && result.text != null) {
                        bubbleSink.onAssistantMessage(result.text);
                    }
                });
            }

            @Override
            public void onError(String message) {
                main.post(() -> {
                    if (bubbleSink != null) bubbleSink.onError(message);
                    setSending(false);
                });
            }

            @Override
            public void onLlmStart() {
                main.post(() -> {
                    if (bubbleSink != null) {
                        bubbleSink.onStatus(appContext.getString(R.string.copilot_status_thinking));
                    }
                });
            }
        };
    }

    private void setSending(boolean value) {
        sending = value;
        BubbleSink sink = bubbleSink;
        if (sink != null) sink.onSendingChanged(value);
    }

    @Override
    public void onReply(String text, boolean toolFired) {
        if (bubbleSink == null || TextUtils.isEmpty(text)) return;
        main.post(() -> {
            if (!toolFired) bubbleSink.onAssistantMessage(text);
        });
    }

    @Override
    public void onToolResult(ToolResult result) {
    }

    @Override
    public void onError(String message) {
        if (bubbleSink == null) return;
        main.post(() -> bubbleSink.onError(message));
    }

    @Override
    public boolean onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
        if (bubbleSink == null || TextUtils.isEmpty(question)) return false;
        main.post(() -> bubbleSink.onConfirmNeeded(question, onConfirm, onCancel));
        return true;
    }
}
