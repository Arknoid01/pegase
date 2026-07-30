package com.pegasuscorp.orbe.chat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.pegasuscorp.orbe.contextstore.AttachedContextInjector;
import com.pegasuscorp.orbe.llm.PegasePrompt;
import com.pegasuscorp.orbe.memory.ConversationHistorySelector;
import com.pegasuscorp.orbe.memory.MemoryPromptBuilder;
import com.pegasuscorp.orbe.tools.OpenAiToolSchemaBuilder;
import com.pegasuscorp.orbe.tools.ToolRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client OpenAI-compatible (Groq / Cerebras / OpenRouter).
 */
public final class OpenAiCompatibleChatBackend implements ChatBackend {

    private final Context appContext;
    private final LlmProvider provider;
    private final String modelOverride;
    private final int maxRateLimitRetries;
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public OpenAiCompatibleChatBackend(Context context, LlmProvider provider) {
        this(context, provider, null, 0);
    }

    public OpenAiCompatibleChatBackend(Context context, LlmProvider provider,
            String modelOverride, int maxRateLimitRetries) {
        this.appContext = context.getApplicationContext();
        this.provider = provider;
        this.modelOverride = modelOverride;
        this.maxRateLimitRetries = Math.max(0, maxRateLimitRetries);
    }

    public LlmProvider provider() {
        return provider;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public String traceBackendLabel() {
        return provider.displayName + "/" + currentModelId();
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback) {
        send(history, userMessage, callback, ChatSendOptions.legacy());
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback,
            ChatSendOptions options) {
        if (TextUtils.isEmpty(provider.apiKey)) {
            callback.onError("Clé " + provider.displayName
                    + " manquante. Va dans Réglages → Clés API.");
            return;
        }
        ChatSendOptions opts = options != null ? options : ChatSendOptions.legacy();
        boolean stream = callback instanceof StreamOnReply && !opts.nativeTools;
        io.execute(() -> {
            try {
                String body = buildBody(history, userMessage, stream, opts);
                if (stream) {
                    streamPostWithRetry(body, (StreamOnReply) callback);
                } else {
                    String raw = postWithRetry(body);
                    LlmReply reply = GroqCompletionParser.parse(raw);
                    main.post(() -> callback.onLlmReply(reply));
                }
            } catch (Exception e) {
                android.util.Log.e("OpenAiChat", provider.id + " erreur", e);
                String spoken = ChatSpokenErrors.toUserMessage(
                        provider.displayName, e.getMessage());
                main.post(() -> callback.onError(spoken));
            }
        });
    }

    /** Appel synchrone (thread IO) — pour MultiProviderBackend. */
    public LlmReply sendBlocking(List<Turn> history, String userMessage,
            ChatSendOptions options) throws Exception {
        if (TextUtils.isEmpty(provider.apiKey)) {
            throw new IllegalStateException("Clé " + provider.displayName + " manquante");
        }
        ChatSendOptions opts = options != null ? options : ChatSendOptions.legacy();
        String body = buildBody(history, userMessage, false, opts);
        String raw = postWithRetry(body);
        return GroqCompletionParser.parse(raw);
    }

    public LlmReply sendAgenticBlocking(AgenticChain chain, ChatSendOptions options)
            throws Exception {
        if (TextUtils.isEmpty(provider.apiKey)) {
            throw new IllegalStateException("Clé " + provider.displayName + " manquante");
        }
        if (chain == null || chain.isEmpty()) return LlmReply.text("");
        ChatSendOptions opts = options != null ? options : ChatSendOptions.legacy();
        String body = buildAgenticBody(chain, opts, false);
        String raw = postWithRetry(body);
        return GroqCompletionParser.parse(raw);
    }

    /** Synthèse agentique finale en streaming (voix). Thread IO — callbacks sur main. */
    void streamAgenticContinuation(AgenticChain chain, ChatSendOptions options,
            StreamOnReply callback) throws Exception {
        if (TextUtils.isEmpty(provider.apiKey)) {
            throw new IllegalStateException("Clé " + provider.displayName + " manquante");
        }
        if (chain == null || chain.isEmpty()) {
            main.post(() -> callback.onReply(""));
            return;
        }
        ChatSendOptions opts = options != null ? options : ChatSendOptions.legacy();
        String body = buildAgenticBody(chain, opts, true);
        streamPostWithRetry(body, callback);
    }

    private void streamPostWithRetry(String body, StreamOnReply callback) throws Exception {
        int attempt = 0;
        while (true) {
            try {
                streamPostOnce(body, callback);
                return;
            } catch (LlmRateLimitException e) {
                attempt++;
                if (attempt > maxRateLimitRetries) throw e;
                long waitMs = Math.min(e.retryAfterMs > 0 ? e.retryAfterMs : 6000L, 15_000L);
                Thread.sleep(waitMs);
            }
        }
    }

    private void streamPostOnce(String body, StreamOnReply callback) throws Exception {
        HttpURLConnection conn = openPost(body);
        int code = conn.getResponseCode();
        if (code == 429) {
            String errBody = readStream(conn.getErrorStream());
            long retryAfterMs = parseRetryAfterMs(errBody, conn.getHeaderField("retry-after"));
            throw new LlmRateLimitException(provider.id, retryAfterMs);
        }
        if (code >= 400) {
            throw new RuntimeException(provider.displayName + " HTTP " + code + " : "
                    + readStream(conn.getErrorStream()));
        }
        StringBuilder accumulated = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;
                String delta = extractStreamDelta(data);
                if (delta == null || delta.isEmpty()) continue;
                accumulated.append(delta);
                String snap = accumulated.toString();
                main.post(() -> callback.onPartial(snap));
            }
        }
        String full = PegasePrompt.sanitizeForSpeech(accumulated.toString().trim());
        main.post(() -> callback.onReply(full));
    }

    private String postWithRetry(String body) throws Exception {
        int attempt = 0;
        while (true) {
            try {
                return post(body);
            } catch (LlmRateLimitException e) {
                attempt++;
                if (attempt > maxRateLimitRetries) throw e;
                long waitMs = Math.min(e.retryAfterMs > 0 ? e.retryAfterMs : 6000L, 15_000L);
                Thread.sleep(waitMs);
            }
        }
    }

    private String post(String body) throws Exception {
        try {
            HttpURLConnection conn = openPost(body);
            int code = conn.getResponseCode();
            String bodyText = readStream(code < 400 ? conn.getInputStream() : conn.getErrorStream());
            if (code == 429) {
                long retryAfterMs = parseRetryAfterMs(bodyText, conn.getHeaderField("retry-after"));
                throw new LlmRateLimitException(provider.id, retryAfterMs);
            }
            if (code >= 400) {
                throw new RuntimeException(provider.displayName + " HTTP " + code + " : " + bodyText);
            }
            return bodyText;
        } catch (SocketTimeoutException e) {
            throw new java.util.concurrent.TimeoutException(
                    "Timeout " + provider.displayName + " (" + provider.readTimeoutMs + " ms)");
        }
    }

    private HttpURLConnection openPost(String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(provider.chatCompletionsUrl)
                .openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + provider.apiKey);
        for (Map.Entry<String, String> h : provider.extraHeaders.entrySet()) {
            conn.setRequestProperty(h.getKey(), h.getValue());
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout(provider.connectTimeoutMs);
        conn.setReadTimeout(provider.readTimeoutMs);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    private String buildBody(List<Turn> history, String userMessage, boolean stream,
            ChatSendOptions options) throws Exception {
        boolean nativeTools = options.nativeTools && provider.nativeFc
                && CloudModelStore.isToolCapableModel(currentModelId());
        JSONArray messages = new JSONArray();
        messages.put(message("system",
                MemoryPromptBuilder.buildFullSystem(appContext, userMessage, nativeTools)));
        List<Turn> promptHistory = ConversationHistorySelector.selectForPrompt(
                appContext, history, userMessage);
        for (Turn turn : promptHistory) {
            if (turn.system) {
                messages.put(message("system", turn.text));
            } else {
                messages.put(message(turn.fromUser ? "user" : "assistant", turn.text));
            }
        }
        messages.put(message("user",
                AttachedContextInjector.wrapUserMessage(appContext, userMessage)));

        JSONObject root = new JSONObject();
        root.put("model", currentModelId());
        root.put("messages", messages);
        root.put("temperature", 0.92);
        root.put("max_tokens", options.replyMaxTokens());
        if (stream) root.put("stream", true);
        if (nativeTools) {
            JSONArray tools = OpenAiToolSchemaBuilder.build(toolRegistry, options.allowedTools);
            root.put("tools", tools);
            root.put("tool_choice", "auto");
            root.put("parallel_tool_calls", false);
        }
        applyQwenReasoningFormat(root, currentModelId());
        return root.toString();
    }

    private String buildAgenticBody(AgenticChain chain, ChatSendOptions options, boolean stream)
            throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(message("system", MemoryPromptBuilder.buildFullSystem(
                appContext, chain.userMessage, options.allowMoreTools, !options.allowMoreTools)));
        List<Turn> promptHistory = ConversationHistorySelector.selectForPrompt(
                appContext, chain.history, chain.userMessage);
        for (Turn turn : promptHistory) {
            if (turn.system) {
                messages.put(message("system", turn.text));
            } else {
                messages.put(message(turn.fromUser ? "user" : "assistant", turn.text));
            }
        }
        for (AgenticChain.Step step : chain.steps()) {
            messages.put(assistantToolCallMessage(step.assistantReply, step.toolCall));
            messages.put(toolResultMessage(step.toolCall, step.toolResultContent));
            // Hint adapté : search/wiki → synthèse ; notepad/spotify → confirmation courte
            String toolName = step.toolCall != null ? step.toolCall.name : "outil";
            String result = step.toolDisplayText != null && !step.toolDisplayText.isEmpty()
                    ? step.toolDisplayText : step.toolResultContent;
            messages.put(message("system", ToolSuccessHint.build(toolName, result)));
        }

        JSONObject root = new JSONObject();
        root.put("model", currentModelId());
        root.put("messages", messages);
        root.put("temperature", 0.92);
        root.put("max_tokens", options.allowMoreTools ? 512 : options.replyMaxTokens());
        if (stream) {
            root.put("stream", true);
        }
        if (options.allowMoreTools && provider.nativeFc
                && CloudModelStore.isToolCapableModel(currentModelId())) {
            JSONArray tools = OpenAiToolSchemaBuilder.build(toolRegistry, options.allowedTools);
            root.put("tools", tools);
            root.put("tool_choice", "auto");
            root.put("parallel_tool_calls", false);
        }
        applyQwenReasoningFormat(root, currentModelId());
        return root.toString();
    }

    private String currentModelId() {
        return modelOverride != null ? modelOverride : provider.modelId;
    }

    private static JSONObject message(String role, String content) throws Exception {
        return new JSONObject().put("role", role).put("content", content);
    }

    private static void applyQwenReasoningFormat(JSONObject root, String modelId)
            throws Exception {
        if (CloudModelStore.isGroqQwenModel(modelId)) {
            root.put("reasoning_format", "hidden");
        }
    }

    private static String extractStreamDelta(String jsonLine) {
        try {
            JSONObject obj = new JSONObject(jsonLine);
            JSONArray choices = obj.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
            if (delta == null) return null;
            return delta.optString("content", null);
        } catch (Exception e) {
            return null;
        }
    }

    static long parseRetryAfterMs(String errorBody, String retryAfterHeader) {
        long raw = 0L;
        if (retryAfterHeader != null) {
            try {
                raw = (long) (Double.parseDouble(retryAfterHeader.trim()) * 1000);
            } catch (NumberFormatException ignored) {
            }
        }
        if (raw <= 0) {
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("try again in ([0-9.]+)s")
                        .matcher(errorBody != null ? errorBody : "");
                if (m.find()) raw = (long) (Double.parseDouble(m.group(1)) * 1000) + 500;
            } catch (Exception ignored) {
            }
        }
        if (raw <= 0) return 6_000L;
        return Math.min(raw, 15_000L);
    }

    private static String readStream(java.io.InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private static JSONObject assistantToolCallMessage(LlmReply assistantReply,
            NativeToolCall call) throws Exception {
        JSONObject msg = new JSONObject();
        msg.put("role", "assistant");
        String content = assistantReply != null ? assistantReply.content : null;
        if (content != null && !content.trim().isEmpty()) {
            msg.put("content", content.trim());
        } else {
            msg.put("content", JSONObject.NULL);
        }
        JSONArray toolCalls = new JSONArray();
        JSONObject tc = new JSONObject();
        String callId = call.id != null && !call.id.isEmpty() ? call.id : "call_agentic";
        tc.put("id", callId);
        tc.put("type", "function");
        JSONObject fn = new JSONObject();
        fn.put("name", call.name);
        fn.put("arguments", call.arguments != null ? call.arguments.toString() : "{}");
        tc.put("function", fn);
        toolCalls.put(tc);
        msg.put("tool_calls", toolCalls);
        return msg;
    }

    private static JSONObject toolResultMessage(NativeToolCall call, String content)
            throws Exception {
        JSONObject msg = new JSONObject();
        msg.put("role", "tool");
        String callId = call.id != null && !call.id.isEmpty() ? call.id : "call_agentic";
        msg.put("tool_call_id", callId);
        msg.put("content", content != null ? content : "");
        return msg;
    }

    @Override
    public void sendAgenticContinuation(AgenticChain chain, ChatSendOptions options,
            OnReply callback) {
        if (TextUtils.isEmpty(provider.apiKey)) {
            callback.onError("Clé " + provider.displayName + " manquante.");
            return;
        }
        if (chain == null || chain.isEmpty()) {
            callback.onLlmReply(LlmReply.text(""));
            return;
        }
        ChatSendOptions opts = options != null ? options : ChatSendOptions.legacy();
        boolean stream = callback instanceof StreamOnReply && !opts.allowMoreTools;
        io.execute(() -> {
            try {
                if (stream) {
                    streamAgenticContinuation(chain, opts, (StreamOnReply) callback);
                } else {
                    LlmReply reply = sendAgenticBlocking(chain, opts);
                    main.post(() -> callback.onLlmReply(reply));
                }
            } catch (Exception e) {
                main.post(() -> callback.onError(e.getMessage()));
            }
        });
    }
}
