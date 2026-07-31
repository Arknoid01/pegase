package com.pegasuscorp.orbe.iface;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.pegasuscorp.orbe.ConversationChatAdapter;
import com.pegasuscorp.orbe.PortraitActivity;
import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.chat.ChatSessionRegistry;
import com.pegasuscorp.orbe.chat.ChatVoiceBridge;
import com.pegasuscorp.orbe.chat.OpenRouterVisionClient;
import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.fs.UriDisplayNames;
import com.pegasuscorp.orbe.memory.MemoryRepository;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.session.SessionObserver;
import com.pegasuscorp.orbe.tools.PegaseInterfaceData;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.knowledge.NasaImageHelper;
import com.pegasuscorp.orbe.ui.OrbeTokens;
import com.pegasuscorp.orbe.ui.PegaseSheets;
import com.pegasuscorp.orbe.ui.ThinkingView;
import com.pegasuscorp.orbe.voice.PegaseVisualPhase;
import com.pegasuscorp.orbe.voice.PegaseVisualStateHub;
import com.pegasuscorp.orbe.voice.PegaseWakeController;
import com.pegasuscorp.orbe.voice.PttTouchHelper;
import com.pegasuscorp.orbe.voice.VoiceManager;
import com.pegasuscorp.orbe.voice.VoicePushToTalk;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.pegasuscorp.orbe.iface.IfaceUi.C_CARD;
import static com.pegasuscorp.orbe.iface.IfaceUi.C_MUTED;
import static com.pegasuscorp.orbe.iface.IfaceUi.dp;
import static com.pegasuscorp.orbe.iface.IfaceUi.makeIconButton;
import static com.pegasuscorp.orbe.iface.IfaceUi.matchWrap;
import static com.pegasuscorp.orbe.iface.IfaceUi.matchWeight;
import static com.pegasuscorp.orbe.iface.IfaceUi.showBottomSheet;

/** Onglet Discussion (RecyclerView + ThinkingView + envoi texte). */
public class DiscussionFragment extends Fragment {

    public static final int REQ_CHAT_PICK_MD = 4713;
    public static final int REQ_CHAT_PICK_IMAGE = 4714;
    public static final int REQ_CHAT_PICK_PDF = 4715;

    private PegaseInterfaceHost host;
    private PegaseInterfaceViewModel viewModel;

    private boolean memoryBannerCollapsed;
    private TextView conversationEmptyView;
    private RecyclerView conversationList;
    private ConversationChatAdapter conversationAdapter;
    private TextView memoryBannerView;
    private EditText chatInput;
    private Button chatSendBtn;
    private Button chatPttBtn;
    private TextView chatContextsLine;
    private TextView visionAttachBadge;
    private ThinkingView thinkingView;
    private String lastConversationText = "";
    private MemoryRepository.OnTurnsChangedListener turnsListener;
    private PegaseVisualStateHub.Listener visualStateListener;

    /** Pièce jointe vision en attente (image ou PDF) — envoi au prochain ↗. */
    @Nullable private Uri pendingVisionUri;
    private boolean pendingVisionPdf;
    @Nullable private String pendingVisionName;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (!(context instanceof PegaseInterfaceHost)) {
            throw new IllegalStateException("Host must implement PegaseInterfaceHost");
        }
        host = (PegaseInterfaceHost) context;
        viewModel = host.getInterfaceViewModel();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        memoryBannerCollapsed = true;
        memoryBannerView = new TextView(ctx);
        memoryBannerView.setTextColor(Color.parseColor("#AA35D0DD"));
        memoryBannerView.setTextSize(11);
        memoryBannerView.setTypeface(OrbeTokens.typeLight());
        memoryBannerView.setMaxLines(1);
        memoryBannerView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        memoryBannerView.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
        GradientDrawable bannerBg = new GradientDrawable();
        bannerBg.setCornerRadius(dp(ctx, OrbeTokens.RADIUS_SM));
        bannerBg.setColor(Color.parseColor("#1435D0DD"));
        bannerBg.setStroke(dp(ctx, 1), Color.parseColor("#3335D0DD"));
        memoryBannerView.setBackground(bannerBg);
        memoryBannerView.setOnClickListener(v -> {
            PegaseSheets.haptic(v);
            PortraitActivity.open(ctx);
        });
        memoryBannerView.setOnLongClickListener(v -> {
            PegaseSheets.haptic(v);
            memoryBannerCollapsed = !memoryBannerCollapsed;
            refreshMemoryBanner();
            return true;
        });
        LinearLayout.LayoutParams bannerLp = matchWrap();
        bannerLp.bottomMargin = dp(ctx, 6);
        root.addView(memoryBannerView, bannerLp);
        refreshMemoryBanner();

        conversationEmptyView = new TextView(ctx);
        conversationEmptyView.setText("— Aucun message. Écris à Pégase ci-dessous.");
        conversationEmptyView.setTextColor(Color.parseColor(C_MUTED));
        conversationEmptyView.setTextSize(14);
        conversationEmptyView.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));
        root.addView(conversationEmptyView, matchWrap());

        conversationList = new RecyclerView(ctx);
        conversationList.setLayoutManager(new LinearLayoutManager(ctx));
        conversationList.setClipToPadding(false);
        conversationList.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        conversationAdapter = new ConversationChatAdapter();
        conversationList.setAdapter(conversationAdapter);
        root.addView(conversationList, matchWeight());

        thinkingView = new ThinkingView(ctx);
        LinearLayout.LayoutParams thinkLp = matchWrap();
        thinkLp.topMargin = dp(ctx, 6);
        thinkLp.bottomMargin = dp(ctx, 2);
        root.addView(thinkingView, thinkLp);

        visualStateListener = phase -> runOnUi(() -> {
            if (!isAdded() || thinkingView == null) return;
            if (host != null && !host.isDiscussionTabVisible()) return;
            if (phase == PegaseVisualPhase.MIC_LISTENING) {
                thinkingView.onMicListening();
            } else if (phase == PegaseVisualPhase.THINKING) {
                thinkingView.onLlmStart();
            } else if (phase == PegaseVisualPhase.IDLE) {
                thinkingView.onMicIdle();
            }
        });
        PegaseVisualStateHub.addListener(visualStateListener);

        try {
            MemoryRepository.getInstance(ctx).reloadRecentTurnsFromDisk();
        } catch (Exception ignored) {
        }
        lastConversationText = "";
        refreshConversationIfNeeded();

        chatContextsLine = new TextView(ctx);
        chatContextsLine.setTextSize(11);
        chatContextsLine.setTextColor(Color.parseColor("#88FFFFFF"));
        chatContextsLine.setPadding(0, dp(ctx, 4), 0, 0);
        chatContextsLine.setVisibility(View.GONE);
        root.addView(chatContextsLine, matchWrap());

        visionAttachBadge = new TextView(ctx);
        visionAttachBadge.setTextSize(12);
        visionAttachBadge.setTextColor(Color.parseColor("#E0FFFFFF"));
        visionAttachBadge.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
        visionAttachBadge.setVisibility(View.GONE);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(ctx, OrbeTokens.RADIUS_SM));
        badgeBg.setColor(Color.parseColor("#2235D0DD"));
        badgeBg.setStroke(dp(ctx, 1), Color.parseColor("#5535D0DD"));
        visionAttachBadge.setBackground(badgeBg);
        visionAttachBadge.setOnClickListener(v -> {
            PegaseSheets.haptic(v);
            clearPendingVision();
            Toast.makeText(requireContext(), "Pièce jointe retirée", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams badgeLp = matchWrap();
        badgeLp.topMargin = dp(ctx, 6);
        root.addView(visionAttachBadge, badgeLp);

        LinearLayout inputRow = new LinearLayout(ctx);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, dp(ctx, 10), 0, 0);
        root.addView(inputRow, matchWrap());

        chatInput = new EditText(ctx);
        chatInput.setHint("Écris à Pégase…");
        chatInput.setHintTextColor(Color.parseColor("#55FFFFFF"));
        chatInput.setTextColor(Color.WHITE);
        chatInput.setBackgroundColor(Color.parseColor(C_CARD));
        chatInput.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        chatInput.setMinHeight(dp(ctx, 44));
        chatInput.setMaxLines(4);
        chatInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        chatInput.setRawInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        chatInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        chatInput.setOnEditorActionListener((textView, actionId, event) -> {
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || enter) {
                sendTextChatMessage();
                return true;
            }
            return false;
        });
        chatInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) scrollConversationToEnd();
        });
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inputRow.addView(chatInput, inputLp);

        chatPttBtn = makeIconButton(ctx, "🎤");
        chatPttBtn.setContentDescription(ctx.getString(com.pegasuscorp.orbe.R.string.voice_ptt_mic));
        PttTouchHelper.attach(chatPttBtn, ctx, requireActivity(),
                VoicePushToTalk.Channel.DISCUSSION, new VoicePushToTalk.Callback() {
                    @Override
                    public void onTranscript(String text) {
                        if (!isAdded() || chatInput == null) return;
                        chatInput.setText(text);
                        sendTextChatMessage();
                    }

                    @Override
                    public void onListeningChanged(boolean listening) {
                        if (!isAdded() || chatPttBtn == null) return;
                        chatPttBtn.setAlpha(listening ? 1f : 0.75f);
                    }
                });
        inputRow.addView(chatPttBtn);

        chatSendBtn = makeIconButton(ctx, "↗");
        chatSendBtn.setContentDescription("Envoyer");
        chatSendBtn.setOnClickListener(v -> sendTextChatMessage());
        inputRow.addView(chatSendBtn);

        Button newDiscussionBtn = makeIconButton(ctx, "＋");
        newDiscussionBtn.setContentDescription("Nouvelle discussion");
        newDiscussionBtn.setOnClickListener(v -> confirmStartNewDiscussion());
        inputRow.addView(newDiscussionBtn);

        Button convMenu = makeIconButton(ctx, "⋮");
        convMenu.setOnClickListener(v -> showConversationMenu());
        inputRow.addView(convMenu);

        refreshChatContextsLine();
        deliverPendingChatPhrase();

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        applyDiscussionTabMode(true);
        ensureTurnsListener();
        refreshConversationIfNeeded();
        deliverPendingChatPhrase();
    }

    @Override
    public void onStart() {
        super.onStart();
        // Garder le listener même si un autre onglet est visible (ViewPager pause).
        ensureTurnsListener();
    }

    @Override
    public void onPause() {
        applyDiscussionTabMode(false);
        // Ne pas retirer le listener ici — sinon addTurn n'actualise plus la liste.
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (visualStateListener != null) {
            PegaseVisualStateHub.removeListener(visualStateListener);
            visualStateListener = null;
        }
        clearTurnsListener();
        conversationList = null;
        conversationAdapter = null;
        chatInput = null;
        chatSendBtn = null;
        chatPttBtn = null;
        visionAttachBadge = null;
        thinkingView = null;
        super.onDestroyView();
    }

    @Override
    public void onDetach() {
        clearTurnsListener();
        host = null;
        viewModel = null;
        super.onDetach();
    }

    private void ensureTurnsListener() {
        if (getContext() == null) return;
        if (turnsListener == null) {
            turnsListener = this::refreshConversationIfNeeded;
        }
        MemoryRepository.getInstance(requireContext()).setOnTurnsChangedListener(turnsListener);
    }

    private void clearTurnsListener() {
        if (getContext() != null && turnsListener != null) {
            MemoryRepository repo = MemoryRepository.getInstance(requireContext());
            if (repo.getOnTurnsChangedListener() == turnsListener) {
                repo.setOnTurnsChangedListener(null);
            }
        }
        turnsListener = null;
    }

    public boolean hasChatInput() {
        return chatInput != null;
    }

    public void requestChatFocus() {
        if (chatInput == null) return;
        chatInput.post(() -> {
            if (chatInput == null || getContext() == null) return;
            chatInput.setFocusable(true);
            chatInput.setFocusableInTouchMode(true);
            chatInput.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(chatInput,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    public void invalidateConversationCache() {
        lastConversationText = "";
    }

    public void scrollConversationToEnd() {
        if (conversationList == null || conversationAdapter == null) return;
        if (host != null && !host.isDiscussionTabVisible()) return;
        int n = conversationAdapter.getItemCount();
        if (n > 0) {
            conversationList.scrollToPosition(n - 1);
        }
    }

    public void refreshConversationIfNeeded() {
        if (conversationList == null || conversationAdapter == null || getContext() == null) {
            return;
        }
        refreshMemoryBanner();
        List<PegaseInterfaceData.ChatMessageUi> messages =
                PegaseInterfaceData.conversationMessages(requireContext());
        String signature = conversationSignature(messages);
        if (signature.equals(lastConversationText)) return;
        lastConversationText = signature;
        conversationAdapter.submit(messages);
        if (conversationEmptyView != null) {
            conversationEmptyView.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
        }
        boolean visible = isResumed()
                && (host == null || host.isDiscussionTabVisible());
        if (visible) {
            conversationList.post(this::scrollConversationToEnd);
        }
    }

    private static String conversationSignature(List<PegaseInterfaceData.ChatMessageUi> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append(messages.size()).append('|');
        for (PegaseInterfaceData.ChatMessageUi m : messages) {
            sb.append(m.fromUser ? 'U' : 'A').append(':')
                    .append(m.text != null ? m.text.length() : 0).append(':')
                    .append(m.text != null ? m.text.hashCode() : 0).append(':')
                    .append(m.reasoning != null ? 'R' : '-').append(';');
        }
        return sb.toString();
    }

    private void applyDiscussionTabMode(boolean active) {
        Context ctx = getContext();
        if (ctx == null) return;
        if (active) {
            PegaseWakeController.setTextDiscussionActive(true);
            PegaseWakeController.pauseWake(ctx);
            VoiceManager voice = ChatVoiceBridge.getSharedVoice(ctx);
            if (voice != null) {
                voice.cancelScheduledListening();
                voice.stopListening();
            }
            return;
        }
        PegaseWakeController.setTextDiscussionActive(false);
        if (!ChatSessionRegistry.isActive() && !PegaseWakeController.isVoiceChatActive()) {
            PegaseWakeController.resumeWakeIfAllowed(ctx);
        }
    }

    /** Livré aussi depuis PegaseInterfaceActivity (onNewIntent / onResume). */
    public void deliverPendingChatPhrase() {
        if (viewModel == null || chatInput == null) return;
        String phrase = viewModel.takePendingChatPhrase();
        if (phrase == null || phrase.isEmpty()) return;
        chatInput.setText(phrase);
        chatInput.post(this::sendTextChatMessage);
    }

    private void showConversationMenu() {
        showBottomSheet(requireContext(), "Discussion", new String[]{
                "Envoyer",
                "Nouvelle discussion",
                "Joindre / contextes .md",
                "Analyser une image",
                "Analyser un PDF",
                "Exporter la conversation",
                "Actualiser"
        }, which -> {
            if (which == 0) sendTextChatMessage();
            else if (which == 1) confirmStartNewDiscussion();
            else if (which == 2) showChatMdAttachMenu();
            else if (which == 3) pickImageForVision();
            else if (which == 4) pickPdfForVision();
            else if (which == 5) exportConversation();
            else if (which == 6) {
                lastConversationText = "";
                refreshConversationIfNeeded();
            }
        });
    }

    private void confirmStartNewDiscussion() {
        if (viewModel != null && viewModel.isChatSending()) {
            Toast.makeText(requireContext(),
                    "Attends la fin de la réponse en cours.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean hasHistory = false;
        try {
            hasHistory = !PegaseInterfaceData.conversationMessages(requireContext()).isEmpty()
                    || ChatSessionRegistry.isActive();
        } catch (Exception ignored) {}
        if (!hasHistory) {
            startNewDiscussion();
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Nouvelle discussion ?")
                .setMessage("Termine celle-ci (résumé en mémoire) et en démarre une vide.")
                .setPositiveButton("Nouvelle", (d, w) -> startNewDiscussion())
                .setNegativeButton("Annuler", null)
                .show();
    }

    /** Fin de session + historique vide, prêt pour un nouveau fil. */
    private void startNewDiscussion() {
        Context ctx = requireContext();
        if (viewModel != null) viewModel.setChatSending(false);
        if (chatSendBtn != null) chatSendBtn.setEnabled(true);
        if (thinkingView != null) thinkingView.reset();

        boolean saved = false;
        try {
            saved = ChatSessionRegistry.finalizeSession();
        } catch (Exception ignored) {}
        try {
            MemoryRepository.getInstance(ctx).clearRecentTurns();
        } catch (Exception ignored) {}
        try {
            ChatSessionRegistry.recreate(ctx);
        } catch (Exception ignored) {}

        if (chatInput != null) chatInput.setText("");
        clearPendingVision();
        lastConversationText = "";
        refreshConversationIfNeeded();
        if (host != null) host.updateSubtitle();
        Toast.makeText(ctx, saved
                        ? "Discussion terminée — nouvelle conversation"
                        : "Nouvelle conversation",
                Toast.LENGTH_SHORT).show();
        if (chatInput != null) chatInput.requestFocus();
    }

    private void showChatMdAttachMenu() {
        CharSequence[] items = new CharSequence[]{
                "Charger un contexte Pégase",
                "Importer un .md du téléphone",
                "Décharger tous les contextes"
        };
        new AlertDialog.Builder(requireContext())
                .setTitle("Documents .md")
                .setItems(items, (d, which) -> {
                    if (which == 0) pickContextsToLoadForChat();
                    else if (which == 1) pickChatMdFromPhone();
                    else {
                        ContextualFileStore.getInstance(requireContext()).unload("tout");
                        refreshChatContextsLine();
                        Toast.makeText(requireContext(), "Contextes déchargés",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void pickContextsToLoadForChat() {
        ContextualFileStore store = ContextualFileStore.getInstance(requireContext());
        // Projets Bureau absents des contextes → les proposer / synchroniser
        ensureBureauProjectsAsContexts(requireContext(), store);

        List<ContextualFileStore.Meta> metas = store.listContexts();
        if (metas.isEmpty()) {
            Toast.makeText(requireContext(),
                    "Aucun plan / contexte .md — crée un plan dans le Bureau d'abord.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[metas.size()];
        boolean[] checked = new boolean[metas.size()];
        for (int i = 0; i < metas.size(); i++) {
            ContextualFileStore.Meta m = metas.get(i);
            labels[i] = m.filename + (m.loaded ? " ✓" : "");
            checked[i] = m.loaded;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Contextes à charger")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton("OK", (d, w) -> {
                    for (int i = 0; i < metas.size(); i++) {
                        ContextualFileStore.Meta m = metas.get(i);
                        if (checked[i]) {
                            store.load(m.keyword);
                        } else if (m.loaded) {
                            store.unload(m.keyword);
                        }
                    }
                    refreshChatContextsLine();
                    Toast.makeText(requireContext(),
                            "Contextes mis à jour — Pégase les lit au prochain message",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    /** Miroir des plans Bureau → contextes chat (pour import conversation). */
    private static void ensureBureauProjectsAsContexts(Context ctx, ContextualFileStore store) {
        if (ctx == null || store == null) return;
        try {
            for (String slug : com.pegasuscorp.orbe.bureau.BureauProjectStore.listSlugs(ctx)) {
                if (slug == null || slug.isEmpty()) continue;
                if (store.contextExists(slug)) continue;
                String md = com.pegasuscorp.orbe.bureau.BureauProjectStore.loadMarkdown(ctx, slug);
                if (md == null || md.trim().isEmpty()) {
                    com.pegasuscorp.orbe.bureau.BureauProject p =
                            com.pegasuscorp.orbe.bureau.BureauProjectStore.load(ctx, slug);
                    if (p != null) {
                        md = com.pegasuscorp.orbe.bureau.BureauMarkdownBuilder.render(p);
                    }
                }
                if (md != null && !md.trim().isEmpty()) {
                    store.save(slug, md);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void pickImageForVision() {
        if (!ensureOpenRouterReady()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQ_CHAT_PICK_IMAGE);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Sélecteur d'image indisponible",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void pickPdfForVision() {
        if (!ensureOpenRouterReady()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQ_CHAT_PICK_PDF);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Sélecteur PDF indisponible",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private boolean ensureOpenRouterReady() {
        if (viewModel != null && viewModel.isChatSending()) {
            Toast.makeText(requireContext(), "Attends la fin de la réponse en cours.",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!ApiKeyStore.hasOpenRouterKey(requireContext())) {
            Toast.makeText(requireContext(),
                    "Clé OpenRouter requise (Réglages → Clés API).",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private String visionPromptFromInput(boolean pdf) {
        String typed = chatInput != null ? chatInput.getText().toString().trim() : "";
        if (!typed.isEmpty()) return typed;
        return pdf
                ? "Analyse ce PDF : résumé clair et points importants."
                : "Analyse cette image : décris, lis le texte, dis l'essentiel.";
    }

    /** Attache image/PDF : badge + focus prompt — l'analyse part à l'envoi. */
    private void attachPendingVision(Uri uri, boolean pdf) {
        if (uri == null || getContext() == null) return;
        try {
            requireContext().getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        pendingVisionUri = uri;
        pendingVisionPdf = pdf;
        pendingVisionName = uriDisplayName(uri);
        refreshVisionAttachBadge();
        if (chatInput != null) {
            chatInput.setHint(pdf
                    ? "Que faire de ce PDF ? (puis ↗)"
                    : "Que faire de cette image ? (puis ↗)");
            chatInput.requestFocus();
        }
        Toast.makeText(requireContext(),
                (pdf ? "PDF" : "Image") + " prêt — écris ta consigne puis envoie",
                Toast.LENGTH_SHORT).show();
    }

    private void clearPendingVision() {
        pendingVisionUri = null;
        pendingVisionPdf = false;
        pendingVisionName = null;
        refreshVisionAttachBadge();
        if (chatInput != null) {
            chatInput.setHint("Écris à Pégase…");
        }
    }

    private void refreshVisionAttachBadge() {
        if (visionAttachBadge == null) return;
        if (pendingVisionUri == null) {
            visionAttachBadge.setVisibility(View.GONE);
            visionAttachBadge.setText("");
            return;
        }
        String name = pendingVisionName != null ? pendingVisionName : "fichier";
        if (name.length() > 28) name = name.substring(0, 25) + "…";
        String label = pendingVisionPdf
                ? "📄 PDF · " + name + "  ×"
                : "🖼️ Image · " + name + "  ×";
        visionAttachBadge.setText(label);
        visionAttachBadge.setVisibility(View.VISIBLE);
    }

    private void startVisionAnalysis(Uri uri, boolean pdf, String prompt) {
        if (uri == null || getContext() == null || viewModel == null) return;
        Context ctx = requireContext();
        String usePrompt = prompt != null ? prompt.trim() : "";
        if (usePrompt.isEmpty()) {
            usePrompt = visionPromptFromInput(pdf);
            // visionPromptFromInput lit encore le champ — forcer défaut si vide après clear
            if (usePrompt.isEmpty()) {
                usePrompt = pdf
                        ? "Analyse ce PDF : résumé clair et points importants."
                        : "Analyse cette image : décris, lis le texte, dis l'essentiel.";
            }
        }

        String displayName = uriDisplayName(uri);
        String userLabel = (pdf ? "📄 Analyse PDF : " : "🖼️ Analyse image : ")
                + displayName + "\n« " + usePrompt + " »";

        if (!ChatSessionRegistry.get(ctx).isActive()) {
            PegaseWakeController.setTextDiscussionActive(true);
            PegaseWakeController.pauseWake(ctx);
        }
        ensureTurnsListener();
        MemoryRepository.getInstance(ctx).addTurn(true, userLabel);

        viewModel.setChatSending(true);
        if (chatSendBtn != null) chatSendBtn.setEnabled(false);
        if (thinkingView != null) {
            thinkingView.reset();
            PegaseWakeController.setAssistantThinking(true);
            thinkingView.onLlmStart();
        }
        if (host != null) host.updateSubtitle();
        lastConversationText = "";
        refreshConversationIfNeeded();
        Toast.makeText(ctx, "Analyse OpenRouter…", Toast.LENGTH_SHORT).show();

        final String finalPrompt = usePrompt;
        OpenRouterVisionClient.Callback cb = new OpenRouterVisionClient.Callback() {
            @Override
            public void onSuccess(String analysis) {
                if (!isAdded()) return;
                MemoryRepository.getInstance(requireContext()).addTurn(false, analysis);
                lastConversationText = "";
                refreshConversationIfNeeded();
                if (thinkingView != null) thinkingView.onComplete();
                PegaseWakeController.setAssistantThinking(false);
                try {
                    ChatVoiceBridge.getSharedVoice(requireActivity()).speak(analysis, null);
                } catch (Exception ignored) {}
                finishTextChatReply();
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                String err = message != null ? message : "Analyse impossible";
                MemoryRepository.getInstance(requireContext()).addTurn(false, err);
                lastConversationText = "";
                refreshConversationIfNeeded();
                if (thinkingView != null) thinkingView.onError();
                PegaseWakeController.setAssistantThinking(false);
                Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show();
                finishTextChatReply();
            }
        };

        if (pdf) {
            OpenRouterVisionClient.analyzePdfUri(ctx, uri, displayName, finalPrompt, cb);
        } else {
            OpenRouterVisionClient.analyzeImageUri(ctx, uri, finalPrompt, cb);
        }
    }

    private String uriDisplayName(Uri uri) {
        return UriDisplayNames.fromUri(getContext(), uri, "fichier");
    }

    private void pickChatMdFromPhone() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "text/markdown", "text/plain", "text/*", "application/octet-stream"
            });
            startActivityForResult(intent, REQ_CHAT_PICK_MD);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Sélecteur de fichiers indisponible",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CHAT_PICK_MD
                && resultCode == android.app.Activity.RESULT_OK
                && data != null && data.getData() != null) {
            onChatPickMdResult(data.getData());
        } else if (requestCode == REQ_CHAT_PICK_IMAGE
                && resultCode == android.app.Activity.RESULT_OK
                && data != null && data.getData() != null) {
            attachPendingVision(data.getData(), false);
        } else if (requestCode == REQ_CHAT_PICK_PDF
                && resultCode == android.app.Activity.RESULT_OK
                && data != null && data.getData() != null) {
            attachPendingVision(data.getData(), true);
        }
    }

    public void onChatPickMdResult(Uri uri) {
        if (uri == null || getContext() == null) return;
        try {
            String name = UriDisplayNames.fromUri(requireContext(), uri, "import.md");
            if (!name.toLowerCase(Locale.ROOT).endsWith(".md")) {
                name = name + ".md";
            }
            String content = readChatUriUtf8(uri);
            if (TextUtils.isEmpty(content)) {
                Toast.makeText(requireContext(), "Fichier vide", Toast.LENGTH_SHORT).show();
                return;
            }
            final String label = name;
            final String body = content;
            final String keyword = name.replace("-context.md", "")
                    .replace(".md", "")
                    .replace('_', ' ')
                    .trim();
            new AlertDialog.Builder(requireContext())
                    .setTitle("Importer « " + label + " »")
                    .setMessage("Enregistrer dans tes contextes Pégase et le charger "
                            + "pour cette discussion ?")
                    .setPositiveButton("Sauver + charger", (d, w) -> {
                        ContextualFileStore store =
                                ContextualFileStore.getInstance(requireContext());
                        store.save(keyword, body);
                        String msg = store.load(keyword);
                        refreshChatContextsLine();
                        if (msg == null) {
                            Toast.makeText(requireContext(),
                                    "Fichier sauvé mais pas chargé — réessaie via « Charger un contexte »",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(requireContext(),
                                    msg + " Pégase le lira au prochain message.",
                                    Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNeutralButton("Charger sans sauver", (d, w) -> {
                        ContextualFileStore store =
                                ContextualFileStore.getInstance(requireContext());
                        String tempKey = "import-" + System.currentTimeMillis();
                        store.save(tempKey, body);
                        String msg = store.load(tempKey);
                        refreshChatContextsLine();
                        if (msg == null) {
                            Toast.makeText(requireContext(), "Échec du chargement temporaire",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(requireContext(),
                                    "Contexte temporaire chargé — lu au prochain message",
                                    Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Import impossible : " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private String readChatUriUtf8(Uri uri) throws Exception {
        InputStream in = requireContext().getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalStateException("stream null");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private void refreshChatContextsLine() {
        if (chatContextsLine == null || getContext() == null) return;
        List<String> names =
                ContextualFileStore.getInstance(requireContext()).getLoadedDisplayNames();
        if (names == null || names.isEmpty()) {
            chatContextsLine.setVisibility(View.GONE);
            chatContextsLine.setText("");
            chatContextsLine.setOnClickListener(null);
            return;
        }
        StringBuilder sb = new StringBuilder("Joints : ");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(names.get(i));
        }
        sb.append("  ·  tap pour gérer");
        chatContextsLine.setText(sb.toString());
        chatContextsLine.setVisibility(View.VISIBLE);
        chatContextsLine.setOnClickListener(v -> showChatMdAttachMenu());
    }

    private void applyMemoryBannerCollapse() {
        if (memoryBannerView == null || getContext() == null) return;
        if (memoryBannerCollapsed) {
            memoryBannerView.setMaxLines(1);
            memoryBannerView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        } else {
            memoryBannerView.setMaxLines(8);
            memoryBannerView.setEllipsize(null);
            memoryBannerView.setLineSpacing(dp(requireContext(), 2), 1f);
        }
    }

    private void refreshMemoryBanner() {
        if (memoryBannerView == null || getContext() == null) return;
        String reminder = PegaseInterfaceData.formatMemoryReminder(requireContext());
        if (reminder.isEmpty()) {
            memoryBannerView.setText("Portrait · ce qu'elle croit savoir de toi");
            memoryBannerView.setVisibility(View.VISIBLE);
            memoryBannerCollapsed = true;
            applyMemoryBannerCollapse();
            return;
        }
        if (memoryBannerCollapsed) {
            String oneLine = reminder.replace("\n", " · ");
            if (oneLine.length() > 64) oneLine = oneLine.substring(0, 61) + "…";
            memoryBannerView.setText("Portrait · " + oneLine);
        } else {
            memoryBannerView.setText(reminder);
        }
        memoryBannerView.setVisibility(View.VISIBLE);
        applyMemoryBannerCollapse();
    }

    private void sendTextChatMessage() {
        if (chatInput == null || viewModel == null) return;
        if (viewModel.isChatSending()) {
            Toast.makeText(requireContext(), "Envoi en cours…", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = chatInput.getText().toString().trim();

        // Image / PDF attaché → analyse vision avec le prompt saisi
        if (pendingVisionUri != null) {
            if (!ensureOpenRouterReady()) return;
            Uri uri = pendingVisionUri;
            boolean pdf = pendingVisionPdf;
            String prompt = text;
            chatInput.setText("");
            clearPendingVision();
            startVisionAnalysis(uri, pdf, prompt);
            return;
        }

        if (text.isEmpty()) return;

        Context ctx = requireContext();
        if (Trace.looksLikeStressToggle(text)) {
            Trace.setStressTest(!Trace.isStressTest());
            chatInput.setText("");
            Toast.makeText(ctx, Trace.isStressTest()
                            ? "Mode test activé — événements marqués stress"
                            : "Mode test désactivé — usage réel",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (!ChatSessionRegistry.get(ctx).isActive()) {
            PegaseWakeController.setTextDiscussionActive(true);
            PegaseWakeController.pauseWake(ctx);
        }

        ensureTurnsListener();
        chatInput.setText("");
        viewModel.setChatSending(true);
        if (chatSendBtn != null) chatSendBtn.setEnabled(false);
        if (thinkingView != null) {
            thinkingView.reset();
        }
        if (host != null) host.updateSubtitle();

        // addTurn est synchrone dans send() — rafraîchir après pour voir la bulle user.
        PegaseSession.get(ctx).send(text, textSessionObserver());
        lastConversationText = "";
        refreshConversationIfNeeded();
    }

    private SessionObserver textSessionObserver() {
        return new SessionObserver() {
            @Override
            public void onToolStart(String toolId) {
                runOnUi(() -> {
                    if (!isAdded() || thinkingView == null) return;
                    if (host != null && !host.isDiscussionTabVisible()) return;
                    PegaseWakeController.setAssistantThinking(true);
                    thinkingView.onToolStart(toolId);
                });
            }

            @Override
            public void onToolEnd(String toolId, boolean ok) {
                runOnUi(() -> {
                    if (!isAdded() || thinkingView == null) return;
                    if (host != null && !host.isDiscussionTabVisible()) return;
                    thinkingView.onToolEnd(toolId, ok);
                });
            }

            @Override
            public void onLlmStart() {
                runOnUi(() -> {
                    if (!isAdded() || thinkingView == null) return;
                    if (host != null && !host.isDiscussionTabVisible()) return;
                    PegaseWakeController.setAssistantThinking(true);
                    thinkingView.onLlmStart();
                });
            }

            @Override
            public void onReply(String reply, boolean toolFired) {
                runOnUi(() -> {
                    lastConversationText = "";
                    refreshConversationIfNeeded();
                    if (toolFired) return;
                    PegaseWakeController.setAssistantThinking(false);
                    if (thinkingView != null && isAdded()) thinkingView.onComplete();
                    finishTextChatReply();
                });
            }

            @Override
            public void onToolResult(ToolResult result) {
                runOnUi(() -> handleSessionToolResult(result));
            }

            @Override
            public void onToolExit(ToolResult result) {
                runOnUi(() -> {
                    PegaseWakeController.setAssistantThinking(false);
                    if (thinkingView != null && isAdded()) thinkingView.onComplete();
                    handleSessionToolResult(result);
                });
            }

            @Override
            public void onToolBlocked() {
                runOnUi(() -> {
                    PegaseWakeController.setAssistantThinking(false);
                    if (thinkingView != null && isAdded()) thinkingView.onError();
                    if (isAdded()) {
                        Toast.makeText(requireContext(),
                                "Action bloquée — déverrouille la session.",
                                Toast.LENGTH_LONG).show();
                    }
                    finishTextChatReply();
                });
            }

            @Override
            public void onError(String message) {
                runOnUi(() -> {
                    PegaseWakeController.setAssistantThinking(false);
                    if (thinkingView != null && isAdded()) thinkingView.onError();
                    if (isAdded() && message != null && !message.isEmpty()) {
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                    }
                    finishTextChatReply();
                });
            }

            @Override
            public void onToolProgress(String message) {
                runOnUi(() -> {
                    if (!isAdded()) return;
                    if (message == null || message.isEmpty()) return;
                    if (host != null && !host.isDiscussionTabVisible()) {
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                    }
                    if (host != null) host.updateSubtitle();
                });
            }

            @Override
            public boolean onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                return false;
            }

            @Override
            public boolean onChoiceNeeded(String title, String[] labels,
                    java.util.function.IntConsumer onChosen, Runnable onCancel) {
                return false;
            }
        };
    }

    private void runOnUi(Runnable r) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(r);
        } else if (viewModel != null) {
            // Fragment détaché : débloquer l'envoi quand même.
            r.run();
        }
    }

    private void handleSessionToolResult(ToolResult result) {
        if (result != null
                && result.kind == ToolResult.Kind.IMAGE_URL
                && result.imageUrl != null && !result.imageUrl.isEmpty()
                && isAdded()) {
            NasaImageHelper.showImageUrl(requireContext(), result.imageUrl);
        }
        if (thinkingView != null && isAdded()) thinkingView.onComplete();
        finishTextChatReply();
    }

    private void finishTextChatReply() {
        if (viewModel != null) viewModel.setChatSending(false);
        if (!isAdded()) return;
        if (chatSendBtn != null) chatSendBtn.setEnabled(true);
        lastConversationText = "";
        refreshConversationIfNeeded();
        if (host != null) host.updateSubtitle();
        if (chatInput != null) {
            chatInput.requestFocus();
        }
    }

    private void exportConversation() {
        Context ctx = requireContext();
        String text = PegaseInterfaceData.formatConversation(ctx);
        try {
            File dir = new File(ctx.getCacheDir(), "generated");
            if (!dir.exists()) dir.mkdirs();
            String name = "conversation_pegase_"
                    + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.FRENCH).format(new Date())
                    + ".txt";
            File out = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(ctx, "Exporté : " + name, Toast.LENGTH_SHORT).show();
            if (host != null) {
                host.openFilesTab();
                host.refreshFilesTab();
            }
        } catch (Exception e) {
            Toast.makeText(ctx, "Export impossible", Toast.LENGTH_SHORT).show();
        }
    }
}
