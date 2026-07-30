package com.pegasuscorp.orbe.orion;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.pegasuscorp.orbe.session.ChatConfirmBridge;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.ui.PegaseSheets;

import org.json.JSONObject;

import java.util.Locale;

/**
 * En-tête Orion : pastille de statut, label uptime/coût, Lancer / Éteindre.
 * Les ticks UI/coût sont pilotés par le coordinateur ({@link #onUiTick()}, {@link #onCostTick()}).
 */
public final class OrionStatusView {

    public interface Listener {
        void onLaunchRequested();
        void onStopRequested();
    }

    private final Activity activity;
    private Listener listener;

    private View root;
    private TextView statusDot;
    private TextView statusLabel;
    private Button launchBtn;
    private Button stopPodBtn;
    private Button comfyBtn;
    private Button copyComfyUrlBtn;

    public OrionStatusView(Activity activity) {
        this.activity = activity;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public View build() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, OrionUi.dp(activity, 4));

        TextView title = new TextView(activity);
        title.setText("⚡ Orion");
        title.setTextColor(Color.parseColor(OrionUi.CYAN));
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title);

        statusDot = new TextView(activity);
        statusDot.setTextSize(12);
        statusDot.setPadding(OrionUi.dp(activity, 8), 0, OrionUi.dp(activity, 4), 0);
        header.addView(statusDot);

        statusLabel = new TextView(activity);
        statusLabel.setTextColor(Color.WHITE);
        statusLabel.setTextSize(12);
        statusLabel.setMaxLines(2);
        statusLabel.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(statusLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        launchBtn = OrionUi.cyanBtn(activity, "Lancer");
        launchBtn.setTextSize(11);
        launchBtn.setOnClickListener(v -> {
            PegaseSheets.haptic(v);
            if (listener != null) listener.onLaunchRequested();
            else performLaunch();
        });
        header.addView(launchBtn);

        stopPodBtn = OrionUi.outlineBtn(activity, "Éteindre");
        stopPodBtn.setTextSize(11);
        stopPodBtn.setOnClickListener(v -> {
            PegaseSheets.haptic(v);
            if (listener != null) listener.onStopRequested();
            else performStop();
        });
        stopPodBtn.setVisibility(View.GONE);
        header.addView(stopPodBtn);

        column.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        comfyBtn = OrionUi.outlineBtn(activity, "Lance Comfy");
        comfyBtn.setTextSize(11);
        comfyBtn.setVisibility(View.GONE);
        comfyBtn.setOnClickListener(v -> {
            PegaseSheets.haptic(v);
            performStartComfy();
        });
        LinearLayout.LayoutParams comfyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        comfyLp.topMargin = OrionUi.dp(activity, 2);
        column.addView(comfyBtn, comfyLp);

        copyComfyUrlBtn = OrionUi.outlineBtn(activity, "Aucun pod Comfy actif");
        copyComfyUrlBtn.setTextSize(11);
        copyComfyUrlBtn.setEnabled(false);
        copyComfyUrlBtn.setOnClickListener(v -> {
            PegaseSheets.haptic(v);
            copyComfyUiUrl();
        });
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyLp.topMargin = OrionUi.dp(activity, 2);
        column.addView(copyComfyUrlBtn, copyLp);

        root = column;
        return column;
    }

    public View getRoot() {
        return root;
    }

    /** Appelé par le coordinateur (tick 1 s). */
    public void onUiTick() {
        OrionStatus st = OrionStateStore.get().getStatus();
        if (st == OrionStatus.READY || st == OrionStatus.BUSY || st == OrionStatus.STARTING) {
            updateMetaAndCost();
        }
    }

    /** Appelé par le coordinateur (tick 60 s). */
    public void onCostTick() {
        updateMetaAndCost();
    }

    public void refreshUi(OrionStatus status) {
        OrionStateStore store = OrionStateStore.get();
        String progress = store.progressLine();
        String detail = store.getStatusDetail();
        String lastError = store.getLastError();
        switch (status) {
            case OFFLINE:
                statusDot.setText(!TextUtils.isEmpty(lastError) ? "⚠" : "🔴");
                if (!TextUtils.isEmpty(progress)) {
                    statusLabel.setText(progress);
                    statusLabel.setTextColor(Color.parseColor("#FF8A80"));
                } else {
                    statusLabel.setText("Hors ligne");
                    statusLabel.setTextColor(Color.WHITE);
                }
                launchBtn.setVisibility(View.VISIBLE);
                stopPodBtn.setVisibility(View.GONE);
                setComfyButton(true, "Lance Comfy");
                setCopyComfyUrlButton(false);
                break;
            case STARTING:
                statusDot.setText("🟡");
                statusLabel.setTextColor(Color.parseColor("#FFD54F"));
                statusLabel.setMaxLines(2);
                statusLabel.setText(!TextUtils.isEmpty(progress) ? progress
                        : (!TextUtils.isEmpty(detail) ? detail : "Pod démarre…"));
                launchBtn.setVisibility(View.GONE);
                stopPodBtn.setVisibility(View.GONE);
                setComfyButton(false, "Lance Comfy");
                setCopyComfyUrlButton(false);
                break;
            case STOPPING:
                statusDot.setText("🟡");
                statusLabel.setTextColor(Color.WHITE);
                statusLabel.setText("Arrêt…");
                launchBtn.setVisibility(View.GONE);
                stopPodBtn.setVisibility(View.GONE);
                setComfyButton(false, "Lance Comfy");
                setCopyComfyUrlButton(false);
                break;
            case BUSY:
                statusDot.setText("⚡");
                statusLabel.setTextColor(Color.parseColor(OrionUi.CYAN));
                statusLabel.setText(!TextUtils.isEmpty(detail) ? detail : "Orion génère...");
                launchBtn.setVisibility(View.GONE);
                stopPodBtn.setVisibility(View.VISIBLE);
                if (store.getPodMode() == PodMode.COMFY) {
                    setComfyButton(true, "Relancer ComfyUI");
                    setCopyComfyUrlButton(true);
                } else {
                    setComfyButton(false, "Lance Comfy");
                    setCopyComfyUrlButton(false);
                }
                break;
            case READY:
            default:
                statusDot.setText("🟢");
                statusLabel.setTextColor(Color.WHITE);
                statusLabel.setMaxLines(1);
                statusLabel.setText(!TextUtils.isEmpty(progress) ? progress : "En ligne");
                launchBtn.setVisibility(View.GONE);
                stopPodBtn.setVisibility(View.VISIBLE);
                if (store.getPodMode() == PodMode.COMFY) {
                    setComfyButton(true, "Relancer ComfyUI");
                    setCopyComfyUrlButton(true);
                } else {
                    // Orion en ligne — pas de second pod ; le start côté store bloque aussi
                    setComfyButton(false, "Lance Comfy");
                    setCopyComfyUrlButton(false);
                }
                break;
        }
        updateMetaAndCost();
    }

    private void setComfyButton(boolean visible, String label) {
        if (comfyBtn == null) return;
        comfyBtn.setVisibility(visible ? View.VISIBLE : View.GONE);
        comfyBtn.setEnabled(visible);
        if (label != null) comfyBtn.setText(label);
    }

    /** Actif seulement si pod Comfy READY/BUSY — URL reconstruite depuis le podId courant. */
    private void setCopyComfyUrlButton(boolean active) {
        if (copyComfyUrlBtn == null) return;
        copyComfyUrlBtn.setEnabled(active);
        copyComfyUrlBtn.setText(active
                ? "Copier l'URL ComfyUI"
                : "Aucun pod Comfy actif");
    }

    private void copyComfyUiUrl() {
        String url = OrionStateStore.get().getComfyUiUrl();
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(activity, "Aucun pod Comfy actif", Toast.LENGTH_SHORT).show();
            return;
        }
        if (OrionFragment.copyToClipboard(activity, url)) {
            Toast.makeText(activity, "URL ComfyUI copiée", Toast.LENGTH_SHORT).show();
        }
    }

    public void performStartComfy() {
        OrionStateStore store = OrionStateStore.get();
        OrionStatus st = store.getStatus();
        // Hors ligne → create pod Comfy (choix GPU)
        if (st == OrionStatus.OFFLINE) {
            performLaunchComfyPod();
            return;
        }
        // Pod Comfy déjà prêt → filet POST /start-comfy
        if ((st == OrionStatus.READY || st == OrionStatus.BUSY)
                && store.getPodMode() == PodMode.COMFY) {
            if (comfyBtn != null) comfyBtn.setEnabled(false);
            PodComfyClient.startAsync(activity, new PodComfyClient.Callback() {
                @Override
                public void onDone(String message) {
                    if (comfyBtn != null) comfyBtn.setEnabled(true);
                    Toast.makeText(activity,
                            message != null ? message : "ComfyUI en cours de démarrage (8188)",
                            Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(String error) {
                    if (comfyBtn != null) comfyBtn.setEnabled(true);
                    Toast.makeText(activity,
                            error != null ? error : "Échec ComfyUI",
                            Toast.LENGTH_LONG).show();
                }
            });
            return;
        }
        Toast.makeText(activity, store.mutualExclusionMessage(PodMode.COMFY),
                Toast.LENGTH_LONG).show();
    }

    /** Même flux que Lancer Orion — choix GPU puis create pod setup-comfy.sh. */
    public void performLaunchComfyPod() {
        OrionManagerActions.startComfy(activity, new JSONObject(), new ToolCallback() {
            @Override
            public void onSuccess(ToolResult result) {
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        result != null ? result.text : "OK", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                ChatConfirmBridge.askConfirm(activity, question, onConfirm, onCancel);
            }

            @Override
            public void onChoiceNeeded(String title, String[] labels,
                    java.util.function.IntConsumer onChosen, Runnable onCancel) {
                ChatConfirmBridge.askChoice(activity, title, labels, onChosen, onCancel);
            }

            @Override
            public void onProgress(String message) {
                activity.runOnUiThread(() -> {
                    refreshUi(OrionStateStore.get().getStatus());
                    if (message != null && !message.isEmpty()) {
                        statusLabel.setText(message);
                        statusLabel.setTextColor(Color.parseColor("#FFD54F"));
                    }
                });
            }

            @Override
            public void onError(String error) {
                activity.runOnUiThread(() -> {
                    refreshUi(OrionStateStore.get().getStatus());
                    Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    public void updateMetaAndCost() {
        OrionStateStore store = OrionStateStore.get();
        OrionStatus st = store.getStatus();
        if (st == OrionStatus.OFFLINE || TextUtils.isEmpty(store.getPodId())) {
            if (st == OrionStatus.OFFLINE && TextUtils.isEmpty(store.getLastError())
                    && TextUtils.isEmpty(store.progressLine())) {
                statusLabel.setText("Hors ligne · Lance Orion ou Comfy");
            }
            return;
        }
        if (st == OrionStatus.STARTING || st == OrionStatus.STOPPING) return;
        String gpu = store.getGpuLabel() != null ? store.getGpuLabel() : "GPU";
        String label = st == OrionStatus.BUSY ? "Occupé" : "En ligne";
        statusLabel.setText(String.format(Locale.US,
                "%s · %s · $%.2f/h · %s · ~$%.2f",
                label, gpu, store.getPricePerHour(),
                store.formatUptime(), store.estimatedCost()));
    }

    /** Propose de lancer (confirm) — utilisé par le stream via le coordinateur. */
    public void proposeLaunch() {
        ChatConfirmBridge.askConfirm(activity,
                "Orion est hors ligne. Tu veux que je le lance ?",
                this::performLaunch,
                () -> {});
    }

    public void performLaunch() {
        OrionManagerActions.start(activity, new JSONObject(), new ToolCallback() {
            @Override
            public void onSuccess(ToolResult result) {
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        result != null ? result.text : "OK", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                ChatConfirmBridge.askConfirm(activity, question, onConfirm, onCancel);
            }

            @Override
            public void onChoiceNeeded(String title, String[] labels,
                    java.util.function.IntConsumer onChosen, Runnable onCancel) {
                ChatConfirmBridge.askChoice(activity, title, labels, onChosen, onCancel);
            }

            @Override
            public void onProgress(String message) {
                activity.runOnUiThread(() -> {
                    refreshUi(OrionStateStore.get().getStatus());
                    if (message != null && !message.isEmpty()) {
                        statusLabel.setText(message);
                        statusLabel.setTextColor(Color.parseColor("#FFD54F"));
                    }
                });
            }

            @Override
            public void onError(String error) {
                activity.runOnUiThread(() -> {
                    refreshUi(OrionStateStore.get().getStatus());
                    Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    public void performStop() {
        OrionManagerActions.stop(activity, new ToolCallback() {
            @Override
            public void onSuccess(ToolResult result) {
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        result != null ? result.text : "Arrêté", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onConfirmNeeded(String q, Runnable ok, Runnable no) {
                if (ok != null) ok.run();
            }

            @Override
            public void onError(String error) {
                activity.runOnUiThread(() ->
                        Toast.makeText(activity, error, Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Visuels Lancer / Éteindre selon l'état (complément de {@link #refreshUi}). */
    public void setInputEnabled(boolean ready) {
        // Les boutons pod sont déjà gérés par refreshUi ; méthode conservée pour le contrat.
        if (launchBtn == null) return;
        if (!ready && OrionStateStore.get().getStatus() == OrionStatus.OFFLINE) {
            launchBtn.setVisibility(View.VISIBLE);
        }
    }
}
