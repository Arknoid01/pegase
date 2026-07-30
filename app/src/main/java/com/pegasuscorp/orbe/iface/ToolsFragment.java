package com.pegasuscorp.orbe.iface;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.pegasuscorp.orbe.AtlasSettingsActivity;
import com.pegasuscorp.orbe.KnowMeSettingsActivity;
import com.pegasuscorp.orbe.MemorySettingsActivity;
import com.pegasuscorp.orbe.chat.ChatVoiceBridge;
import com.pegasuscorp.orbe.tools.PegaseInterfaceData;
import com.pegasuscorp.orbe.tools.ToolFavoritesStore;
import com.pegasuscorp.orbe.tools.ToolOrchestrator;
import com.pegasuscorp.orbe.tools.knowledge.NasaReplyHelper;
import com.pegasuscorp.orbe.ui.PegaseSheets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.pegasuscorp.orbe.iface.IfaceUi.C_CARD;
import static com.pegasuscorp.orbe.iface.IfaceUi.C_CYAN;
import static com.pegasuscorp.orbe.iface.IfaceUi.C_MUTED;
import static com.pegasuscorp.orbe.iface.IfaceUi.C_SEP;
import static com.pegasuscorp.orbe.iface.IfaceUi.dp;
import static com.pegasuscorp.orbe.iface.IfaceUi.inputField;
import static com.pegasuscorp.orbe.iface.IfaceUi.makeIconButton;
import static com.pegasuscorp.orbe.iface.IfaceUi.matchWrap;
import static com.pegasuscorp.orbe.iface.IfaceUi.matchWeight;
import static com.pegasuscorp.orbe.iface.IfaceUi.padded;
import static com.pegasuscorp.orbe.iface.IfaceUi.showBottomSheet;

/** Onglet Outils (phrases / favoris / lancement). */
public class ToolsFragment extends Fragment {

    private PegaseInterfaceHost host;
    private final ToolOrchestrator toolOrchestrator = new ToolOrchestrator();
    private LinearLayout toolsRoot;
    /** Clés de sections ouvertes ; vide = tout replié (défaut). */
    private final Set<String> expandedSections = new HashSet<>();

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (!(context instanceof PegaseInterfaceHost)) {
            throw new IllegalStateException("Host must implement PegaseInterfaceHost");
        }
        host = (PegaseInterfaceHost) context;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, matchWrap());
        toolsRoot = list;
        rebuildToolsList();
        return scroll;
    }

    @Override
    public void onDetach() {
        host = null;
        super.onDetach();
    }

    private void rebuildToolsList() {
        if (toolsRoot == null || getContext() == null) return;
        Context ctx = requireContext();
        toolsRoot.removeAllViews();

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(ctx, 6));
        toolsRoot.addView(header, matchWrap());

        TextView title = new TextView(ctx);
        title.setText("🔧 Outils");
        title.setTextColor(Color.parseColor(C_CYAN));
        title.setTextSize(14);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView apiStatus = new TextView(ctx);
        apiStatus.setText(PegaseInterfaceData.formatApiStatus(ctx));
        apiStatus.setTextColor(Color.parseColor(C_MUTED));
        apiStatus.setTextSize(11);
        apiStatus.setPadding(0, 0, 0, dp(ctx, 10));
        toolsRoot.addView(apiStatus, matchWrap());

        Set<String> pinnedKeys = new HashSet<>(ToolFavoritesStore.getPinnedKeys(ctx));
        List<PegaseInterfaceData.ToolHint> pinned = PegaseInterfaceData.pinnedHints(ctx);
        if (!pinned.isEmpty()) {
            addCollapsibleSection("favoris", "⭐ Favoris", "#FFD54F", pinned);
        }

        for (PegaseInterfaceData.ToolCategory cat : PegaseInterfaceData.toolCategories(ctx)) {
            if (cat.hints.isEmpty()) continue;
            String sectionTitle = cat.title;
            if (cat.emoji != null && !cat.emoji.isEmpty()
                    && !sectionTitle.startsWith(cat.emoji)) {
                sectionTitle = cat.emoji + " " + sectionTitle;
            }
            List<PegaseInterfaceData.ToolHint> visible = new ArrayList<>();
            for (PegaseInterfaceData.ToolHint th : cat.hints) {
                if (!pinnedKeys.contains(th.favoriteKey())) {
                    visible.add(th);
                }
            }
            if (visible.isEmpty()) continue;
            addCollapsibleSection("cat:" + cat.title, sectionTitle, C_CYAN, visible);
        }
    }

    private void addCollapsibleSection(String key, String title, String color,
                                       List<PegaseInterfaceData.ToolHint> hints) {
        Context ctx = requireContext();
        boolean open = expandedSections.contains(key);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(ctx, 10), 0, dp(ctx, 4));
        header.setClickable(true);
        header.setFocusable(true);

        TextView chevron = new TextView(ctx);
        chevron.setText(open ? "▾" : "▸");
        chevron.setTextColor(Color.parseColor(color));
        chevron.setTextSize(13);
        chevron.setTypeface(null, Typeface.BOLD);
        chevron.setPadding(0, 0, dp(ctx, 6), 0);
        header.addView(chevron);

        TextView label = new TextView(ctx);
        label.setText(title + "  ·  " + hints.size());
        label.setTextColor(Color.parseColor(color));
        label.setTextSize(13);
        label.setTypeface(null, Typeface.BOLD);
        header.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        for (PegaseInterfaceData.ToolHint th : hints) {
            body.addView(makeToolRow(th), matchWrap());
        }

        header.setOnClickListener(v -> {
            PegaseSheets.haptic(v);
            boolean nowOpen = body.getVisibility() != View.VISIBLE;
            body.setVisibility(nowOpen ? View.VISIBLE : View.GONE);
            chevron.setText(nowOpen ? "▾" : "▸");
            if (nowOpen) expandedSections.add(key);
            else expandedSections.remove(key);
        });

        toolsRoot.addView(header, matchWrap());
        toolsRoot.addView(body, matchWrap());
    }

    private View makeToolRow(PegaseInterfaceData.ToolHint hint) {
        Context ctx = requireContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 6), dp(ctx, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(C_CARD));
        bg.setCornerRadius(dp(ctx, 8));
        bg.setStroke(dp(ctx, 1), Color.parseColor(C_SEP));
        row.setBackground(bg);
        LinearLayout.LayoutParams rowLp = matchWrap();
        rowLp.bottomMargin = dp(ctx, 4);
        row.setLayoutParams(rowLp);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        row.addView(textCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView label = new TextView(ctx);
        label.setText(hint.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(label, matchWrap());

        if (hint.phrase != null && !hint.phrase.isEmpty()) {
            TextView phrase = new TextView(ctx);
            phrase.setText(hint.phrase);
            phrase.setTextColor(Color.parseColor(C_MUTED));
            phrase.setTextSize(11);
            phrase.setMaxLines(1);
            phrase.setEllipsize(android.text.TextUtils.TruncateAt.END);
            phrase.setPadding(0, dp(ctx, 1), 0, 0);
            textCol.addView(phrase, matchWrap());
        }

        Button play = makeIconButton(ctx, "▶");
        play.setTextSize(12);
        play.setOnClickListener(v -> {
            PegaseSheets.haptic(v);
            launchTool(hint);
        });
        row.addView(play);

        Button menu = makeIconButton(ctx, "⋮");
        menu.setTextSize(14);
        menu.setOnClickListener(v -> {
            PegaseSheets.haptic(v);
            showToolMenu(hint);
        });
        row.addView(menu);

        row.setOnClickListener(v -> {
            PegaseSheets.haptic(v);
            launchTool(hint);
        });
        return row;
    }

    private void showToolMenu(PegaseInterfaceData.ToolHint hint) {
        Context ctx = requireContext();
        boolean pinned = ToolFavoritesStore.isPinned(ctx, hint.favoriteKey());
        showBottomSheet(ctx, hint.label, new String[]{
                "Lancer",
                "Copier la phrase",
                pinned ? "Retirer des favoris" : "Épingler",
                "Voir la phrase"
        }, which -> {
            if (which == 0) launchTool(hint);
            else if (which == 1) copyPhrase(hint.phrase);
            else if (which == 2) {
                ToolFavoritesStore.toggle(ctx, hint.favoriteKey());
                rebuildToolsList();
            } else if (which == 3) {
                new AlertDialog.Builder(ctx)
                        .setTitle(hint.label)
                        .setMessage("« " + hint.phrase + " »")
                        .setPositiveButton("Copier", (d, w) -> copyPhrase(hint.phrase))
                        .setNegativeButton("OK", null)
                        .show();
            }
        });
    }

    private void launchTool(PegaseInterfaceData.ToolHint hint) {
        Context ctx = requireContext();
        switch (hint.toolId) {
            case "know_me":
                startActivity(new Intent(ctx, KnowMeSettingsActivity.class));
                break;
            case "memory_settings":
                startActivity(new Intent(ctx, MemorySettingsActivity.class));
                break;
            case "atlas_settings":
                startActivity(new Intent(ctx, AtlasSettingsActivity.class));
                break;
            case "create_file":
                createRecapDirect();
                break;
            case "sms":
                quickSmsOrPrompt();
                break;
            case "web_search":
                promptWebSearch("Piper TTS français", false);
                break;
            case "search":
                promptWebSearch("prix bitcoin actuel", true);
                break;
            case "open_interface":
                if (host != null) host.openDiscussionTab();
                showResult(true, "Voici notre discussion récente.");
                break;
            case "notepad":
                if (hint.launchesDirectly()) {
                    runTool(hint.toolCallJson, "notepad");
                } else if (host != null) {
                    host.showTab(PegaseInterfaceHost.TAB_NOTEPAD);
                }
                break;
            case "notifications":
                runTool(hint.toolCallJson != null ? hint.toolCallJson
                        : "{\"tool\":\"notifications\",\"params\":{\"action\":\"list\"}}",
                        "notifications");
                break;
            default:
                if (hint.launchesDirectly()) {
                    runTool(hint.toolCallJson, hint.toolId);
                } else {
                    Toast.makeText(ctx, "Action non disponible", Toast.LENGTH_SHORT).show();
                }
                break;
        }
    }

    private void createRecapDirect() {
        Context ctx = requireContext();
        String preview = PegaseInterfaceData.formatConversation(ctx);
        if (preview.startsWith("Aucune conversation")) {
            showResult(false, "Il n'y a pas encore de discussion à exporter.");
            return;
        }
        String json = PegaseInterfaceData.buildRecapFileToolCall(ctx);
        if (json != null) {
            runTool(json, "create_file");
        } else {
            showResult(false, "Impossible de préparer le fichier.");
        }
    }

    private void quickSmsOrPrompt() {
        String defaultMsg = "J'arrive dans 10 minutes";
        new AlertDialog.Builder(requireContext())
                .setTitle("Message SMS")
                .setMessage("Choisis une action :")
                .setPositiveButton("Phrase exemple", (d, w) -> {
                    String json = PegaseInterfaceData.buildSmsToolCall(defaultMsg);
                    if (json != null) runTool(json, "sms");
                })
                .setNeutralButton("Personnaliser", (d, w) -> promptSms())
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void promptSms() {
        Context ctx = requireContext();
        EditText field = inputField(ctx, "J'arrive dans 10 minutes", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        new AlertDialog.Builder(ctx)
                .setTitle("Message SMS")
                .setMessage("Le composeur s'ouvrira — tu envoies toi-même.")
                .setView(padded(ctx, field))
                .setPositiveButton("Préparer", (d, w) -> {
                    String msg = field.getText().toString().trim();
                    if (msg.isEmpty()) {
                        showResult(false, "Le message est vide.");
                        return;
                    }
                    String json = PegaseInterfaceData.buildSmsToolCall(msg);
                    if (json != null) runTool(json, "sms");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void promptWebSearch(String defaultQuery, boolean tavily) {
        Context ctx = requireContext();
        EditText field = inputField(ctx, defaultQuery, InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(ctx)
                .setTitle(tavily ? "Recherche Tavily" : "Ouvrir Google")
                .setMessage(tavily
                        ? "Recherche intelligente — réponse vocale."
                        : "Ouvre le navigateur sur Google.")
                .setView(padded(ctx, field))
                .setPositiveButton("Chercher", (d, w) -> {
                    String q = field.getText().toString().trim();
                    if (q.isEmpty()) {
                        showResult(false, "Aucune requête de recherche fournie.");
                        return;
                    }
                    String json = tavily
                            ? PegaseInterfaceData.buildTavilyToolCall(q)
                            : PegaseInterfaceData.buildWebSearchToolCall(q);
                    if (json != null) runTool(json, tavily ? "search" : "web_search");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void runTool(String toolCallJson, String toolId) {
        android.app.Activity activity = requireActivity();
        if (!toolOrchestrator.handleIfToolCall(activity, toolCallJson,
                new ToolOrchestrator.ReplyHandler() {
                    @Override
                    public void onSpokenReply(String reply) {
                        handleInterfaceToolReply(reply, toolId);
                    }

                    @Override
                    public void onExitReply(String reply) {
                        ChatVoiceBridge.recordToolReply(reply);
                        ChatVoiceBridge.getSharedVoice(activity).speak(reply, null);
                        showResult(true, reply);
                    }

                    @Override
                    public void onError(String error) {
                        showResult(false, error);
                    }
                })) {
            showResult(false, "Format d'outil invalide.");
        }
    }

    private void handleInterfaceToolReply(String reply, String toolId) {
        Context ctx = requireContext();
        ChatVoiceBridge.recordToolReply(reply);

        if ("nasa".equals(toolId) && reply.startsWith("NASA_IMAGE:")) {
            if (host != null) host.showNasaImage(reply);
            String english = NasaReplyHelper.extractEnglishText(reply);
            if (!english.isEmpty()) {
                ChatVoiceBridge.getSharedVoice(ctx).speak("Une seconde...", null);
                NasaReplyHelper.translate(ctx, english,
                        new NasaReplyHelper.TranslateCallback() {
                            @Override
                            public void onTranslated(String french) {
                                ChatVoiceBridge.recordToolReply(french);
                                if (host != null) {
                                    ChatVoiceBridge.getSharedVoice(ctx)
                                            .speak(french, host::resumeChatListening);
                                    host.refreshDiscussionIfNeeded();
                                }
                            }

                            @Override
                            public void onError(String error) {
                                showResult(false, "Traduction NASA impossible.");
                            }
                        });
            }
            return;
        }

        showResult(true, reply);
        if ("create_file".equals(toolId) && host != null) {
            host.showTab(PegaseInterfaceHost.TAB_FILES);
            host.refreshFilesTab();
        }
        if (host != null) host.refreshDiscussionIfNeeded();
    }

    private void showResult(boolean success, String message) {
        new AlertDialog.Builder(requireContext())
                .setTitle(success ? "Voici ce qu'on a fait" : "Ça n'a pas marché")
                .setMessage(message)
                .setPositiveButton("OK", (d, w) -> {
                    if (success && host != null
                            && host.getInterfaceViewModel().getActiveTabValue()
                            == PegaseInterfaceHost.TAB_FILES) {
                        host.refreshFilesTab();
                    }
                })
                .show();
    }

    private void copyPhrase(String phrase) {
        Context ctx = requireContext();
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("phrase", phrase));
        }
        Toast.makeText(ctx, "Phrase copiée — dis-la à Pégase", Toast.LENGTH_SHORT).show();
    }
}
