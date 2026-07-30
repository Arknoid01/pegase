package com.pegasuscorp.orbe.orion;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.pegasuscorp.orbe.orion.search.OrionCodeIndexService;

import java.util.List;

/**
 * Coordinateur de l'onglet Orion (onglets manuels de PegaseInterfaceActivity —
 * pas un Fragment Android). Compose {@link OrionStatusView}, {@link OrionProjectView}
 * et {@link OrionStreamView}. Observe {@link OrionStateStore} ; ne possède pas l'état pod.
 */
public final class OrionFragment implements OrionStateStore.Observer {

    public static final int REQ_DICTATE = 4711;
    public static final int REQ_PICK_MD = 4712;

    private static final long COST_TICK_MS = 60_000L;
    private static final long UI_TICK_MS = 1_000L;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());

    private OrionStatusView statusView;
    private OrionProjectView projectView;
    private OrionStreamView streamView;

    private boolean attached;
    private final Runnable uiTick = this::onUiTick;
    private final Runnable costTick = this::onCostTick;
    private OrionProjectStore.Observer projectIndexObserver;

    public OrionFragment(Activity activity) {
        this.activity = activity;
    }

    public View build() {
        OrionStateStore.get().attach(activity);

        statusView = new OrionStatusView(activity);
        projectView = new OrionProjectView(activity);
        streamView = new OrionStreamView(activity);

        statusView.setListener(new OrionStatusView.Listener() {
            @Override
            public void onLaunchRequested() {
                statusView.performLaunch();
            }

            @Override
            public void onStopRequested() {
                statusView.performStop();
            }
        });

        streamView.setListener(new OrionStreamView.Listener() {
            @Override
            public void onNeedLaunch() {
                statusView.proposeLaunch();
            }

            @Override
            public void onGenerationFinished(String code) {
                statusView.refreshUi(OrionStateStore.get().getStatus());
                streamView.applyStatusGate(OrionStateStore.get().getStatus());
                if (projectView != null) projectView.refresh();
            }

            @Override
            public void onSaveToProjectRequested(List<OrionFileSession.OrionFile> files) {
                projectView.offerSaveToProject(files);
            }

            @Override
            public boolean isReady() {
                return OrionStateStore.get().getStatus() == OrionStatus.READY;
            }
        });

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        // Ordre d'origine : statut → pièces jointes/label → projet/session → chat/saisie
        root.addView(statusView.build(), OrionUi.matchWrap());
        streamView.build();
        root.addView(streamView.getTopStrip(), OrionUi.matchWrap());
        root.addView(projectView.build(), OrionUi.matchWrap());
        root.addView(streamView.getMainColumn(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusView.refreshUi(OrionStateStore.get().getStatus());
        streamView.applyStatusGate(OrionStateStore.get().getStatus());
        return root;
    }

    /** Clavier ouvert — remonter la conversation. */
    public void onKeyboardVisible() {
        if (streamView != null) streamView.onKeyboardVisible();
    }

    /** Libère le focus du champ Orion (ex. retour Discussion pour push). */
    public void releaseInputFocus() {
        if (streamView != null) streamView.releaseInputFocus();
    }

    public void onAttach() {
        attached = true;
        OrionStateStore.get().attach(activity);
        OrionStateStore.get().addObserver(this);
        if (projectView != null) projectView.onAttach();
        projectIndexObserver = () ->
                OrionCodeIndexService.get().scheduleIndexActiveProject(activity);
        OrionProjectStore.get(activity).addObserver(projectIndexObserver);
        OrionCodeIndexService.get().scheduleIndexActiveProject(activity);
        statusView.refreshUi(OrionStateStore.get().getStatus());
        streamView.applyStatusGate(OrionStateStore.get().getStatus());
        if (projectView != null) projectView.refresh();
        main.removeCallbacks(uiTick);
        main.removeCallbacks(costTick);
        main.post(uiTick);
        main.postDelayed(costTick, COST_TICK_MS);
    }

    public void onDetach() {
        attached = false;
        OrionStateStore.get().removeObserver(this);
        if (projectIndexObserver != null) {
            OrionProjectStore.get(activity).removeObserver(projectIndexObserver);
            projectIndexObserver = null;
        }
        if (projectView != null) projectView.onDetach();
        main.removeCallbacks(uiTick);
        main.removeCallbacks(costTick);
    }

    @Override
    public void onOrionStateChanged(OrionStatus status) {
        if (!attached) return;
        statusView.refreshUi(status);
        streamView.applyStatusGate(status);
    }

    public void onDictateResult(String transcript) {
        if (streamView != null) streamView.onDictateResult(transcript);
    }

    public void onPickMdResult(Uri uri) {
        if (streamView != null) streamView.onPickMdResult(uri);
    }

    /** Préremplit + auto-génère dès READY (file d'attente sinon). */
    public void prefillPrompt(String prompt) {
        if (streamView != null) streamView.prefillPrompt(prompt);
    }

    private void onUiTick() {
        if (!attached) return;
        if (statusView != null) statusView.onUiTick();
        main.postDelayed(uiTick, UI_TICK_MS);
    }

    private void onCostTick() {
        if (!attached) return;
        if (statusView != null) statusView.onCostTick();
        main.postDelayed(costTick, COST_TICK_MS);
    }

    /** Visible tests. */
    public static boolean copyToClipboard(Context ctx, String text) {
        if (ctx == null || TextUtils.isEmpty(text)) return false;
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return false;
        cm.setPrimaryClip(ClipData.newPlainText("orion_code", text));
        return true;
    }
}
