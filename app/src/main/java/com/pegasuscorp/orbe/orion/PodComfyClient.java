package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.tools.HttpJson;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * POST {@code /start-comfy} sur le fileserver pod — lance ComfyUI (route dédiée, pas un shell).
 */
public final class PodComfyClient {

    private static final String TAG = "PodComfy";
    private static final int TIMEOUT_MS = 12_000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pod-comfy");
        t.setDaemon(true);
        return t;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onDone(String message);
        void onError(String error);
    }

    private PodComfyClient() {}

    /** Hors UI thread — POST /start-comfy, même base URL + token que {@link PodFileClient}. */
    public static JSONObject startSync(Context ctx) throws Exception {
        if (ctx == null) throw new IllegalStateException("Contexte manquant");
        if (!PodFileClient.isOnline()) {
            throw new IllegalStateException("Pod hors ligne");
        }
        String base = OrionStateStore.get().getFileServerUrl();
        if (TextUtils.isEmpty(base)) {
            throw new IllegalStateException("URL fileserver absente");
        }
        String token = ApiKeyStore.getOrionToken(ctx);
        Map<String, String> headers = new HashMap<>();
        if (!TextUtils.isEmpty(token)) {
            headers.put("Authorization", "Bearer " + token);
        }
        return HttpJson.postJson(base + "/start-comfy", headers, new JSONObject(),
                TIMEOUT_MS, TIMEOUT_MS);
    }

    public static void startAsync(Context ctx, Callback callback) {
        if (callback == null) return;
        if (ctx == null) {
            MAIN.post(() -> callback.onError("Contexte manquant"));
            return;
        }
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            try {
                JSONObject json = startSync(app);
                String msg = userMessage(json);
                MAIN.post(() -> callback.onDone(msg));
            } catch (Exception e) {
                Log.w(TAG, "start-comfy", e);
                String err = e.getMessage() != null ? e.getMessage() : "Échec ComfyUI";
                MAIN.post(() -> callback.onError(err));
            }
        });
    }

    /** Visible tests. */
    static String userMessage(JSONObject json) {
        if (json == null || !json.optBoolean("ok", false)) {
            String err = json != null ? json.optString("error", "") : "";
            return TextUtils.isEmpty(err) ? "Échec ComfyUI" : err;
        }
        String comfy = json.optString("comfy", "");
        if ("already".equals(comfy)) {
            return "ComfyUI déjà lancé";
        }
        // Optimiste : le pip / boot peut encore tourner en arrière-plan
        return "ComfyUI en cours de démarrage (8188)";
    }
}
