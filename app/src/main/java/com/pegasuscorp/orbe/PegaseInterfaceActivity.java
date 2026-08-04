package com.pegasuscorp.orbe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.pegasuscorp.orbe.chat.ChatSessionRegistry;
import com.pegasuscorp.orbe.chat.ChatVoiceBridge;
import com.pegasuscorp.orbe.iface.DiscussionFragment;
import com.pegasuscorp.orbe.iface.FilesFragment;
import com.pegasuscorp.orbe.iface.IfaceUi;
import com.pegasuscorp.orbe.iface.OrionTabFragment;
import com.pegasuscorp.orbe.iface.PegaseInterfaceHost;
import com.pegasuscorp.orbe.iface.PegaseInterfacePagerAdapter;
import com.pegasuscorp.orbe.iface.PegaseInterfaceViewModel;
import com.pegasuscorp.orbe.llm.LlmEngineManager;
import com.pegasuscorp.orbe.llm.ModelStore;
import com.pegasuscorp.orbe.session.Channel;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.session.SessionContext;
import com.pegasuscorp.orbe.tools.knowledge.NasaImageHelper;
import com.pegasuscorp.orbe.tools.knowledge.NasaReplyHelper;
import com.pegasuscorp.orbe.ui.OrbeTokens;
import com.pegasuscorp.orbe.ui.PegaseSheets;
import com.pegasuscorp.orbe.voice.PegaseWakeController;

/**
 * Coquille Pégase : en-tête + onglets emoji + ViewPager2 (5 Fragments).
 */
public class PegaseInterfaceActivity extends AppCompatActivity implements PegaseInterfaceHost {

    private static final long SUBTITLE_TICK_MS = 1500;

    private PegaseInterfaceViewModel viewModel;
    private ViewPager2 viewPager;
    private View[] tabButtons = new View[5];
    private TextView subtitleView;
    private TextView headerStatusDot;
    private Boolean lastHeaderOnline;
    private boolean subtitleCollapsed;
    private float density;
    private final Handler subtitleHandler = new Handler(Looper.getMainLooper());
    private final Runnable subtitleTick = new Runnable() {
        @Override
        public void run() {
            updateSubtitle();
            if (!isFinishing()) {
                subtitleHandler.postDelayed(this, SUBTITLE_TICK_MS);
            }
        }
    };

    public static void open(Context ctx) {
        PegaseInterfaceState.openOrBringToFront(ctx);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        PegaseInterfaceState.attach(this);
        viewModel = new ViewModelProvider(this).get(PegaseInterfaceViewModel.class);
        PegaseSession.get(this).init(new SessionContext(Channel.TEXT, false));

        if (!android.provider.Settings.canDrawOverlays(this)) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Orbe flottante")
                    .setMessage("Pour rester visible quand tu utilises d'autres apps, "
                            + "Pégase a besoin de s'afficher par-dessus elles.")
                    .setPositiveButton("Autoriser", (d, w) -> startActivity(
                            new android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Plus tard", null)
                    .show();
        }

        density = getResources().getDisplayMetrics().density;
        int pad = dp(14);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(IfaceUi.C_BG));
        root.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, IfaceUi.matchWrap());

        TextView title = new TextView(this);
        title.setText("Pégase");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(OrbeTokens.typeLight());
        title.setOnClickListener(v -> {
            if (subtitleView == null) return;
            PegaseSheets.haptic(v);
            boolean show = subtitleView.getVisibility() != View.VISIBLE;
            subtitleView.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                subtitleCollapsed = false;
                applySubtitleCollapse();
            }
        });
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        headerStatusDot = new TextView(this);
        headerStatusDot.setTextSize(14);
        headerStatusDot.setPadding(0, 0, dp(10), 0);
        headerStatusDot.setOnClickListener(v -> {
            PegaseSheets.haptic(v);
            updateHeaderStatus();
            boolean online = Boolean.TRUE.equals(lastHeaderOnline);
            Toast.makeText(this, online
                            ? "LLM prêt"
                            : "LLM hors ligne — ouvre ⚙️ pour les clés API / modèle local",
                    Toast.LENGTH_SHORT).show();
        });
        header.addView(headerStatusDot);

        Button settingsBtn = IfaceUi.makeIconButton(this, "⚙️");
        settingsBtn.setOnClickListener(v -> {
            PegaseSheets.haptic(v);
            startActivity(new Intent(this, ApiSettingsActivity.class));
        });
        header.addView(settingsBtn);

        TextView subtitle = new TextView(this);
        subtitleView = subtitle;
        updateSubtitle();
        subtitle.setTextColor(Color.parseColor(IfaceUi.C_MUTED));
        subtitle.setTextSize(12);
        subtitle.setTypeface(OrbeTokens.typeLight());
        subtitle.setMaxLines(3);
        subtitle.setPadding(0, dp(4), 0, dp(6));
        subtitle.setVisibility(View.GONE);
        subtitle.setOnClickListener(v -> {
            subtitleCollapsed = !subtitleCollapsed;
            applySubtitleCollapse();
        });
        root.addView(subtitle);

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setClipChildren(true);
        tabRow.setClipToPadding(true);
        tabRow.setPadding(0, dp(2), 0, dp(8));
        root.addView(tabRow, IfaceUi.matchWrap());

        tabButtons[TAB_CONV] = makeTabButton(tabRow, "💬", TAB_CONV);
        tabButtons[TAB_NOTEPAD] = makeTabButton(tabRow, "📝", TAB_NOTEPAD);
        tabButtons[TAB_ORION] = makeTabButton(tabRow, "⚡", TAB_ORION);
        tabButtons[TAB_TOOLS] = makeTabButton(tabRow, "🔧", TAB_TOOLS);
        tabButtons[TAB_FILES] = makeTabButton(tabRow, "📁", TAB_FILES);

        viewPager = new ViewPager2(this);
        viewPager.setId(View.generateViewId());
        viewPager.setOffscreenPageLimit(4);
        viewPager.setAdapter(new PegaseInterfacePagerAdapter(this));
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                viewModel.setActiveTab(position);
                styleAllTabs(position);
            }
        });
        root.addView(viewPager, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottom = Math.max(bars.bottom, ime.bottom);
            root.setPadding(pad + bars.left, bars.top + pad, pad + bars.right, bottom + pad);
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                DiscussionFragment disc = findDiscussionFragment();
                if (disc != null) disc.scrollConversationToEnd();
                // Ne recentrer Orion que si l'onglet Orion est actif
                // (sinon le typeField Orion vole le focus de « Écris à Pégase… »).
                onKeyboardVisible();
            }
            return insets;
        });

        setContentView(root);

        applyAssistantLaunch(getIntent());
        String initialTab = getIntent().getStringExtra(PegaseInterfaceState.EXTRA_TAB);
        if (initialTab != null && !initialTab.isEmpty()) {
            applyTabFromIntent(getIntent());
        } else {
            showTab(TAB_CONV);
        }
        styleAllTabs(viewModel.getActiveTabValue());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyAssistantLaunch(intent);
        applyTabFromIntent(intent);
        deliverPendingChatIfAny();
    }

    /**
     * Deep link {@code pegase://open/...} ou App Action {@code feature} → onglet.
     */
    private void applyAssistantLaunch(Intent intent) {
        if (intent == null) return;
        String tab = null;
        android.net.Uri data = intent.getData();
        if (data != null && "pegase".equals(data.getScheme()) && "open".equals(data.getHost())) {
            String path = data.getPath();
            if (path != null) {
                path = path.replaceFirst("^/", "");
                if ("orion".equalsIgnoreCase(path)) {
                    tab = PegaseInterfaceState.TAB_ORION;
                } else if ("notepad".equalsIgnoreCase(path)) {
                    tab = PegaseInterfaceState.TAB_NOTEPAD;
                } else if (!path.isEmpty()) {
                    tab = PegaseInterfaceState.TAB_CONVERSATION;
                }
            }
            if (tab == null) {
                tab = PegaseInterfaceState.TAB_CONVERSATION;
            }
            intent.putExtra(PegaseInterfaceState.EXTRA_TAB, tab);
            ChatVoiceBridge.onInterfaceOpening();
            return;
        }
        String feature = intent.getStringExtra("feature");
        if (feature == null || feature.isEmpty()) return;
        String f = feature.trim().toLowerCase(java.util.Locale.ROOT);
        if ("orion".equals(f) || f.contains("orion") || f.contains("code")) {
            tab = PegaseInterfaceState.TAB_ORION;
        } else {
            tab = PegaseInterfaceState.TAB_CONVERSATION;
        }
        intent.putExtra(PegaseInterfaceState.EXTRA_TAB, tab);
        ChatVoiceBridge.onInterfaceOpening();
    }

    private void applyTabFromIntent(Intent intent) {
        if (intent == null) return;
        String tab = intent.getStringExtra(PegaseInterfaceState.EXTRA_TAB);
        String orionPrompt = intent.getStringExtra(PegaseInterfaceState.EXTRA_ORION_PROMPT);
        if (orionPrompt != null && !orionPrompt.isEmpty()) {
            PegaseInterfaceState.setPendingOrionPrompt(orionPrompt);
            intent.removeExtra(PegaseInterfaceState.EXTRA_ORION_PROMPT);
        }
        if (tab == null || tab.isEmpty()) {
            if (orionPrompt != null && !orionPrompt.isEmpty()) {
                showTab(TAB_ORION);
                deliverOrionPrefill();
            }
            return;
        }
        if (PegaseInterfaceState.TAB_NOTEPAD.equals(tab)) {
            showTab(TAB_NOTEPAD);
        } else if (PegaseInterfaceState.TAB_ORION.equals(tab)) {
            showTab(TAB_ORION);
            deliverOrionPrefill();
        } else if (PegaseInterfaceState.TAB_TOOLS.equals(tab)) {
            showTab(TAB_TOOLS);
        } else if (PegaseInterfaceState.TAB_FILES.equals(tab)) {
            showTab(TAB_FILES);
        } else {
            showTab(TAB_CONV);
        }
        intent.removeExtra(PegaseInterfaceState.EXTRA_TAB);
    }

    /** Intention Oui → phrase chat même si l'activité était déjà au premier plan. */
    private void deliverPendingChatIfAny() {
        String pending = PegaseInterfaceState.peekPendingChatPhrase();
        if (pending == null || pending.isEmpty()) return;
        showTab(TAB_CONV);
        DiscussionFragment disc = findDiscussionFragment();
        if (disc != null) {
            disc.deliverPendingChatPhrase();
        }
    }

    private void deliverOrionPrefill() {
        OrionTabFragment orion = findOrionTabFragment();
        if (orion != null) {
            orion.deliverOrionPrefill();
        }
    }

    @Override
    public void updateSubtitle() {
        if (subtitleView == null) return;
        updateHeaderStatus();
        if (viewModel != null && viewModel.isChatSending()) {
            subtitleView.setVisibility(View.VISIBLE);
            if (ModelStore.useLocalLlm(this)) {
                boolean loaded = LlmEngineManager.getInstance().getEngine().isModelLoaded();
                if (!loaded) {
                    subtitleView.setText("Chargement du modèle local… (première réponse plus longue)");
                } else {
                    subtitleView.setText("Pégase réfléchit… · modèle local");
                }
            } else {
                subtitleView.setText("Pégase réfléchit…");
            }
            applySubtitleCollapse();
            return;
        }
        if (ChatSessionRegistry.isActive()) {
            subtitleView.setText("Dernière session : discussion texte connectée");
        } else {
            subtitleView.setText("Dernière session : —");
        }
        applySubtitleCollapse();
    }

    private void applySubtitleCollapse() {
        if (subtitleView == null) return;
        if (subtitleCollapsed) {
            subtitleView.setMaxLines(1);
            subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        } else {
            subtitleView.setMaxLines(3);
            subtitleView.setEllipsize(null);
        }
    }

    private void updateHeaderStatus() {
        if (headerStatusDot == null) return;
        boolean online;
        if (ModelStore.useLocalLlm(this)) {
            online = LlmEngineManager.getInstance().getEngine().isModelLoaded();
        } else {
            online = com.pegasuscorp.orbe.chat.ApiKeyStore.hasGroqKey(this)
                    || com.pegasuscorp.orbe.chat.ApiKeyStore.hasGeminiKey(this)
                    || !android.text.TextUtils.isEmpty(
                            com.pegasuscorp.orbe.chat.ApiKeyStore.getOpenRouterKey(this))
                    || !android.text.TextUtils.isEmpty(
                            com.pegasuscorp.orbe.chat.ApiKeyStore.getCerebrasKey(this));
        }
        headerStatusDot.setText(online ? "🟢" : "🔴");
        headerStatusDot.setContentDescription(online ? "LLM connecté" : "LLM hors ligne");
        if (lastHeaderOnline != null && lastHeaderOnline != online) {
            headerStatusDot.animate().cancel();
            headerStatusDot.animate()
                    .scaleX(1.3f).scaleY(1.3f)
                    .setDuration(120)
                    .withEndAction(() -> headerStatusDot.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(140)
                            .start())
                    .start();
            PegaseSheets.haptic(headerStatusDot);
        }
        lastHeaderOnline = online;
    }

    /** Appelé par PegaseInterfaceState quand le modèle Piper change. */
    public void reloadPiperModel() {
        ChatVoiceBridge.reloadSharedPiper();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PegaseSession.get(this).init(new SessionContext(Channel.TEXT, false));
        FloatingOrbService.hide(this);
        updateSubtitle();
        subtitleHandler.removeCallbacks(subtitleTick);
        subtitleHandler.post(subtitleTick);
        resumeChatListening();
        deliverPendingChatIfAny();
    }

    @Override
    protected void onPause() {
        subtitleHandler.removeCallbacks(subtitleTick);
        ChatVoiceBridge.getSharedVoice(this).stopListening();
        // Ne pas afficher l'orbe pendant la fermeture (Back) — la session
        // sera finalisée dans onDestroy / onInterfaceClosed.
        if (!isFinishing()
                && android.provider.Settings.canDrawOverlays(this)) {
            // AlwaysOn copilote gagne : un chat texte/copilote actif ne doit pas
            // basculer l'orbe en mode VOICE (sinon tap → MainActivity au lieu de la bulle).
            if (com.pegasuscorp.orbe.copilot.CopilotPrefs.isAlwaysOn(this)) {
                FloatingOrbService.showCopilot(this);
            } else if (ChatVoiceBridge.isChatActive()) {
                FloatingOrbService.show(this);
            }
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        subtitleHandler.removeCallbacks(subtitleTick);
        PegaseWakeController.setTextDiscussionActive(false);
        PegaseInterfaceState.detach(this);
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        OrionTabFragment orion = findOrionTabFragment();
        if (orion != null && orion.handleActivityResult(requestCode, resultCode, data)) {
            return;
        }
        if (requestCode == DiscussionFragment.REQ_CHAT_PICK_MD
                && resultCode == RESULT_OK && data != null && data.getData() != null) {
            DiscussionFragment disc = findDiscussionFragment();
            if (disc != null) disc.onChatPickMdResult(data.getData());
        }
    }

    @Override
    public Activity getHostActivity() {
        return this;
    }

    @Override
    public PegaseInterfaceViewModel getInterfaceViewModel() {
        return viewModel;
    }

    @Override
    public void openFilesTab() {
        showTab(TAB_FILES);
    }

    @Override
    public void openDiscussionTab() {
        if (isFinishing() || isDestroyed()) return;
        OrionTabFragment orion = findOrionTabFragment();
        if (orion != null) orion.releaseInputFocus();
        showTab(TAB_CONV);
        DiscussionFragment disc = findDiscussionFragment();
        if (disc != null) {
            disc.invalidateConversationCache();
            disc.refreshConversationIfNeeded();
            disc.requestChatFocus();
        }
    }

    @Override
    public void showTab(int tab) {
        if (viewPager == null) return;
        if (tab < 0 || tab >= PegaseInterfacePagerAdapter.TAB_COUNT) tab = TAB_CONV;
        if (tab != TAB_ORION) {
            OrionTabFragment orion = findOrionTabFragment();
            if (orion != null) orion.releaseInputFocus();
        }
        viewModel.setActiveTab(tab);
        viewPager.setCurrentItem(tab, false);
        styleAllTabs(tab);
        if (tab == TAB_ORION) {
            viewPager.post(this::deliverOrionPrefill);
        }
    }

    @Override
    public boolean isDiscussionTabVisible() {
        return viewModel != null
                && viewModel.getActiveTabValue() == TAB_CONV;
    }

    @Override
    public void refreshDiscussionIfNeeded() {
        DiscussionFragment disc = findDiscussionFragment();
        if (disc != null) disc.refreshConversationIfNeeded();
    }

    @Override
    public void refreshFilesTab() {
        FilesFragment files = findFilesFragment();
        if (files != null) files.forceRefresh();
    }

    @Override
    public void onKeyboardVisible() {
        if (viewModel != null && viewModel.getActiveTabValue() == TAB_ORION) {
            OrionTabFragment orion = findOrionTabFragment();
            if (orion != null) orion.onKeyboardVisible();
            return;
        }
        if (viewModel != null && viewModel.getActiveTabValue() == TAB_CONV) {
            DiscussionFragment disc = findDiscussionFragment();
            if (disc != null) disc.requestChatFocus();
        }
    }

    @Override
    public void resumeChatListening() {
        if (!ChatVoiceBridge.isChatActive()) return;
        DiscussionFragment disc = findDiscussionFragment();
        if (isDiscussionTabVisible() && disc != null && disc.hasChatInput()) return;
        subtitleHandler.postDelayed(() -> {
            if (!isFinishing()) {
                ChatVoiceBridge.getSharedVoice(PegaseInterfaceActivity.this)
                        .resumeListeningAfterReply();
            }
        }, 600);
    }

    @Override
    public void showNasaImage(String reply) {
        if (NasaReplyHelper.extractImageUrl(reply).isEmpty()) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Ça n'a pas marché")
                    .setMessage("Pas de photo disponible (vidéo ou contenu non image).")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        NasaImageHelper.show(this, reply);
        refreshDiscussionIfNeeded();
    }

    private DiscussionFragment findDiscussionFragment() {
        return findPagerFragment(TAB_CONV, DiscussionFragment.class);
    }

    private OrionTabFragment findOrionTabFragment() {
        return findPagerFragment(TAB_ORION, OrionTabFragment.class);
    }

    private FilesFragment findFilesFragment() {
        return findPagerFragment(TAB_FILES, FilesFragment.class);
    }

    @SuppressWarnings("unchecked")
    private <T extends Fragment> T findPagerFragment(int position, Class<T> type) {
        // FragmentStateAdapter tags: "f" + itemId (default = position)
        Fragment f = getSupportFragmentManager().findFragmentByTag("f" + position);
        if (type.isInstance(f)) return (T) f;
        for (Fragment child : getSupportFragmentManager().getFragments()) {
            if (type.isInstance(child)) return (T) child;
        }
        return null;
    }

    private Button makeTabButton(LinearLayout row, String label, int tabIndex) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setIncludeFontPadding(false);
        b.setMinHeight(dp(36));
        b.setMinimumHeight(dp(36));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(4), dp(6), dp(4), dp(6));
        b.setStateListAnimator(null);
        b.setElevation(0f);
        b.setOnClickListener(v -> {
            if (viewModel.getActiveTabValue() == tabIndex) return;
            PegaseSheets.haptic(v);
            showTab(tabIndex);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (tabIndex < PegaseInterfacePagerAdapter.TAB_COUNT - 1) {
            lp.setMarginEnd(dp(4));
        }
        row.addView(b, lp);
        return b;
    }

    private void styleAllTabs(int active) {
        for (int i = 0; i < tabButtons.length; i++) {
            styleTab(tabButtons[i], i == active);
        }
    }

    private void styleTab(View v, boolean active) {
        if (!(v instanceof Button)) return;
        Button b = (Button) v;
        b.animate().cancel();
        b.setScaleX(1f);
        b.setScaleY(1f);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(OrbeTokens.RADIUS_SM));
        if (active) {
            // Fond léger + trait cyan — sans scale (sinon le halo déborde hors écran).
            bg.setColor(Color.parseColor("#1835D0DD"));
            bg.setStroke(dp(1), Color.parseColor(IfaceUi.C_CYAN));
            b.setTextColor(Color.parseColor(IfaceUi.C_CYAN));
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(0, Color.TRANSPARENT);
            b.setTextColor(Color.parseColor(IfaceUi.C_MUTED));
        }
        b.setBackground(bg);
    }

    private int dp(int v) {
        return (int) (v * density);
    }
}
