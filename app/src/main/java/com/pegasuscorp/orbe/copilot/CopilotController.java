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
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.copilot.UiActionTool;
import com.pegasuscorp.orbe.voice.PegaseWakeController;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pont entre l'overlay copilote et {@link PegaseSession}.
 * Gère envoi texte, capture écran + vision, et mémorisation contextuelle.
 */
public final class CopilotController {

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
    private volatile String lastUserText = "";
    private volatile int attachGeneration;
    /** Ignore les broadcasts de statut postés avant ce timestamp (anti-race). */
    private volatile long statusClearedAtElapsed;
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
        attachGeneration++;
        bubbleSink = sink;
        PegaseSession session = PegaseSession.get(appContext);
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
                long postedAt = intent.getLongExtra(CopilotStatusBridge.EXTRA_POSTED_AT, 0L);
                if (intent.getBooleanExtra(CopilotStatusBridge.EXTRA_CLEAR, false)) {
                    clearBubbleStatus(false);
                    return;
                }
                String error = intent.getStringExtra(CopilotStatusBridge.EXTRA_ERROR);
                if (bubbleSink != null && !TextUtils.isEmpty(error)) {
                    if (isStaleStatusBroadcast(postedAt)) return;
                    bubbleSink.onError(error);
                    return;
                }
                String status = intent.getStringExtra(CopilotStatusBridge.EXTRA_STATUS);
                if (bubbleSink != null && !TextUtils.isEmpty(status)) {
                    if (isStaleStatusBroadcast(postedAt)) return;
                    bubbleSink.onStatus(status);
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

    private boolean isStaleStatusBroadcast(long postedAt) {
        return postedAt > 0L && postedAt <= statusClearedAtElapsed;
    }

    /** Efface le bandeau haut ; ignore les broadcasts déjà en file. */
    private void clearBubbleStatus(boolean alsoBroadcast) {
        statusClearedAtElapsed = android.os.SystemClock.elapsedRealtime();
        if (bubbleSink != null) bubbleSink.onStatus(null);
        if (alsoBroadcast) CopilotStatusBridge.clearStatus(appContext);
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
        attachGeneration++;
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
        bubbleSink = null;
        sending = false;
        PegaseWakeController.setAssistantThinking(false);
        if (!ChatSessionRegistry.get(appContext).isActive()
                && !PegaseWakeController.isVoiceChatActive()) {
            PegaseWakeController.setTextDiscussionActive(false);
            PegaseWakeController.resumeWakeIfAllowed(appContext);
        }
    }

    public boolean isSending() {
        return sending;
    }

    public void sendUserMessage(String text) {
        if (TextUtils.isEmpty(text) || sending) return;
        String trimmed = text.trim();
        lastUserText = trimmed;
        BubbleSink sink = bubbleSink;
        if (sink != null) sink.onUserMessage(trimmed);

        Context ctx = appContext;

        String payload = buildPayload(trimmed, null);
        setSending(true);
        dispatchUserTurn(trimmed, payload);
    }

    private void dispatchUserTurn(String trimmed, String payloadWithoutReflection) {
        Context ctx = appContext;
        final int gen = attachGeneration;
        if (!CopilotReflectionGate.needsReflection(ctx, trimmed)) {
            main.post(() -> {
                if (gen != attachGeneration) {
                    setSending(false);
                    return;
                }
                PegaseSession.get(ctx).send(payloadWithoutReflection, trimmed, sessionObserver(gen));
            });
            return;
        }

        BubbleSink sink = bubbleSink;
        if (sink != null) {
            sink.onStatus(appContext.getString(R.string.copilot_status_thinking));
        }

        REFLECTION_IO.execute(() -> {
            if (gen != attachGeneration) return;
            String reflectionPlan = "";
            try {
                CopilotScreenContext.Snapshot snap = CopilotScreenContext.readFresh(ctx);
                String prompt = CopilotReflectionPlanner.buildReflectionPrompt(snap, trimmed);
                reflectionPlan = PegaseSession.get(ctx).completeCopilotReflectionSync(prompt);
            } catch (Exception e) {
                android.util.Log.w("CopilotReflection", "reflection skipped", e);
            }
            if (gen != attachGeneration) return;
            String prefix = CopilotReflectionPlanner.buildPayloadPrefix(reflectionPlan);
            String payload = buildPayload(trimmed, prefix.isEmpty() ? null : prefix);
            main.post(() -> {
                if (gen != attachGeneration) {
                    setSending(false);
                    return;
                }
                PegaseSession.get(ctx).send(payload, trimmed, sessionObserver(gen));
            });
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

    private SessionObserver sessionObserver(int gen) {
        return new SessionObserver() {
            @Override
            public void onReply(String text, boolean toolFired) {
                main.post(() -> {
                    if (gen != attachGeneration) return;
                    if (interceptTechnicalViewIdAsk(text, toolFired)) return;
                    clearBubbleStatus(true);
                    if (!toolFired && bubbleSink != null && !TextUtils.isEmpty(text)) {
                        bubbleSink.onAssistantMessage(text);
                    }
                    setSending(false);
                });
            }

            @Override
            public void onPartial(String accumulated) {
                main.post(() -> {
                    if (gen != attachGeneration) return;
                    if (bubbleSink != null) bubbleSink.onAssistantPartial(accumulated);
                });
            }

            @Override
            public void onToolProgress(String message) {
                main.post(() -> {
                    if (gen != attachGeneration) return;
                    if (bubbleSink != null && !TextUtils.isEmpty(message)) {
                        bubbleSink.onStatus(message);
                    }
                });
            }

            @Override
            public void onToolResult(ToolResult result) {
                main.post(() -> {
                    if (gen != attachGeneration) return;
                    clearBubbleStatus(true);
                    if (bubbleSink != null && result != null && result.text != null) {
                        bubbleSink.onAssistantMessage(result.text);
                    }
                    setSending(false);
                });
            }

            @Override
            public void onError(String message) {
                main.post(() -> {
                    if (gen != attachGeneration) return;
                    clearBubbleStatus(true);
                    if (bubbleSink != null) bubbleSink.onError(message);
                    setSending(false);
                });
            }

            @Override
            public void onLlmStart() {
                main.post(() -> {
                    if (gen != attachGeneration) return;
                    if (bubbleSink != null) {
                        bubbleSink.onStatus(appContext.getString(R.string.copilot_status_thinking));
                    }
                });
            }

            @Override
            public boolean onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                main.post(() -> {
                    if (gen != attachGeneration) {
                        if (onCancel != null) onCancel.run();
                        return;
                    }
                    BubbleSink sink = bubbleSink;
                    if (sink != null) {
                        clearBubbleStatus(false);
                        sink.onConfirmNeeded(question, () -> {
                            sink.onStatus(appContext.getString(R.string.copilot_status_action));
                            if (onConfirm != null) onConfirm.run();
                        }, () -> {
                            clearBubbleStatus(true);
                            if (onCancel != null) onCancel.run();
                        });
                    } else if (onCancel != null) {
                        onCancel.run();
                    }
                });
                return true;
            }
        };
    }

    /**
     * Si le LLM demande un viewId technique : ne montre pas la question,
     * relance {@code ui_action} avec le libellé libre de l'utilisateur.
     */
    private boolean interceptTechnicalViewIdAsk(String reply, boolean toolFired) {
        if (toolFired || !CopilotUiAskGuard.asksForTechnicalViewId(reply)) return false;
        String target = CopilotUiAskGuard.inferUiTarget(lastUserText);
        if (TextUtils.isEmpty(target)) return false;
        android.util.Log.w("CopilotUi",
                "LLM asked for technical view id — auto click target=\"" + target + "\"");
        clearBubbleStatus(false);
        if (bubbleSink != null) {
            bubbleSink.onStatus(appContext.getString(R.string.copilot_status_action));
        }
        JSONObject params = new JSONObject();
        try {
            params.put("action", "click");
            params.put("target", target);
        } catch (Exception e) {
            return false;
        }
        final int gen = attachGeneration;
        new UiActionTool().execute(appContext, params, new ToolCallback() {
            @Override
            public void onSuccess(ToolResult result) {
                main.post(() -> {
                    if (gen != attachGeneration) return;
                    clearBubbleStatus(true);
                    if (bubbleSink != null && result != null && !TextUtils.isEmpty(result.text)) {
                        bubbleSink.onAssistantMessage(result.text);
                    }
                    setSending(false);
                });
            }

            @Override
            public void onError(String error) {
                main.post(() -> {
                    if (gen != attachGeneration) return;
                    clearBubbleStatus(true);
                    if (bubbleSink != null) bubbleSink.onError(error);
                    setSending(false);
                });
            }

            @Override
            public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                main.post(() -> {
                    if (gen != attachGeneration) {
                        if (onCancel != null) onCancel.run();
                        return;
                    }
                    if (bubbleSink != null) {
                        bubbleSink.onConfirmNeeded(question, onConfirm, onCancel);
                    } else if (onCancel != null) {
                        onCancel.run();
                    }
                });
            }

            @Override
            public void onProgress(String message) {
                main.post(() -> {
                    if (gen != attachGeneration) return;
                    if (bubbleSink != null && !TextUtils.isEmpty(message)) {
                        bubbleSink.onStatus(message);
                    }
                });
            }
        });
        return true;
    }

    private void setSending(boolean value) {
        sending = value;
        PegaseWakeController.setAssistantThinking(value);
        BubbleSink sink = bubbleSink;
        if (sink != null) sink.onSendingChanged(value);
    }
}
