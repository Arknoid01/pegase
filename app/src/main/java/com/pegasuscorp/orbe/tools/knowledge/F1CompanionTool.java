package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.f1companion.DebriefBuilder;
import com.pegasuscorp.orbe.f1companion.F1CompanionStore;
import com.pegasuscorp.orbe.f1companion.F1FanMemory;
import com.pegasuscorp.orbe.f1companion.F1LivePipeline;
import com.pegasuscorp.orbe.f1companion.F1MemoryStore;
import com.pegasuscorp.orbe.f1companion.FavoriteTeamsStore;
import com.pegasuscorp.orbe.f1companion.WeekendSnapshot;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Compagnon F1 : fiche week-end + live + mémoire fan.
 */
public final class F1CompanionTool implements Tool {

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    public String id() {
        return "f1";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.SEARCH;
    }

    @Override
    public String description() {
        return "f1(action:refresh|status|debrief|discuss|live|memory|remember|predict, "
                + "text?:string, mode?:\"quick\"|\"deep\") — "
                + "Fiche Grand Prix (OpenF1) + mémoire fan. "
                + "refresh / status / debrief / discuss / live comme avant. "
                + "memory = afficher avis/pronostics ; "
                + "remember(text) = noter un avis (« souviens-toi que… ») ; "
                + "predict(text) = enregistrer un pronostic. "
                + "Utilise pour débrief, live, chambrage, « mon pronostic », « souviens-toi ». "
                + "NE PAS utiliser search/Tavily pour un GP déjà en fiche.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String action = params != null
                ? params.optString("action", "debrief").trim().toLowerCase()
                : "debrief";
        String mode = params != null
                ? params.optString("mode", "quick").trim().toLowerCase()
                : "quick";
        String text = params != null ? params.optString("text", "").trim() : "";
        if (text.isEmpty() && params != null) {
            text = params.optString("note", "").trim();
            if (text.isEmpty()) text = params.optString("prediction", "").trim();
        }
        if (mode.isEmpty()) mode = "quick";
        if ("approfondi".equals(mode) || "long".equals(mode) || "full".equals(mode)) {
            mode = "deep";
        }
        if ("rapide".equals(mode) || "short".equals(mode) || "voix".equals(mode)) {
            mode = "quick";
        }
        final String act = action;
        final String md = mode;
        final String tx = text;

        io.execute(() -> {
            try {
                switch (act) {
                    case "refresh":
                    case "update":
                    case "fetch":
                        doRefresh(ctx, cb);
                        break;
                    case "status":
                    case "fiche":
                        doStatus(ctx, cb);
                        break;
                    case "discuss":
                    case "load":
                        doDiscuss(ctx, cb);
                        break;
                    case "live":
                    case "en_direct":
                    case "direct":
                        doLive(ctx, cb);
                        break;
                    case "memory":
                    case "memoire":
                    case "fan":
                        doMemory(ctx, cb);
                        break;
                    case "remember":
                    case "note":
                    case "avis":
                    case "take":
                        doRemember(ctx, tx, cb);
                        break;
                    case "predict":
                    case "pronostic":
                    case "prediction":
                        doPredict(ctx, tx, cb);
                        break;
                    case "debrief":
                    case "analyse":
                    case "analyze":
                    default:
                        doDebrief(ctx, md, cb);
                        break;
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage()
                        : "Impossible de charger la fiche F1.");
            }
        });
    }

    private static void doRefresh(Context ctx, ToolCallback cb) throws Exception {
        WeekendSnapshot snap = F1CompanionStore.ensureFresh(ctx, true);
        int resolved = F1MemoryStore.resolveAgainstRace(ctx, snap);
        String extra = resolved > 0
                ? " " + resolved + " pronostic(s) mis à jour."
                : "";
        cb.onSuccess(ToolResult.text(
                "Fiche mise à jour : " + snap.event + ". Podium : " + snap.podiumLine() + "."
                        + extra,
                synthesis(ctx, snap, "quick")));
    }

    private static void doStatus(Context ctx, ToolCallback cb) throws Exception {
        WeekendSnapshot snap = F1CompanionStore.load(ctx);
        if (snap == null || !snap.hasRaceResults()) {
            snap = F1CompanionStore.ensureFresh(ctx, true);
        } else {
            ContextualFileStore.getInstance(ctx).load(F1CompanionStore.CONTEXT_KEYWORD);
        }
        F1MemoryStore.loadIntoPrompt(ctx);
        cb.onSuccess(ToolResult.text(DebriefBuilder.quickSpeech(snap),
                snap.toMarkdown() + "\n\n" + fanMarkdown(ctx)));
    }

    private static void doDiscuss(Context ctx, ToolCallback cb) throws Exception {
        WeekendSnapshot snap = F1CompanionStore.ensureFresh(ctx, false);
        ContextualFileStore.getInstance(ctx).load(F1CompanionStore.CONTEXT_KEYWORD);
        F1MemoryStore.loadIntoPrompt(ctx);
        cb.onSuccess(ToolResult.text(
                "Fiche " + snap.event + " + mémoire fan chargées. On peut en parler — "
                        + "stratégie, podium, tes pronostics…",
                synthesis(ctx, snap, "deep")));
    }

    private static void doLive(Context ctx, ToolCallback cb) throws Exception {
        String brief = F1LivePipeline.statusBrief(ctx);
        cb.onSuccess(ToolResult.text(brief, brief));
    }

    private static void doMemory(Context ctx, ToolCallback cb) {
        F1FanMemory mem = F1MemoryStore.load(ctx);
        F1MemoryStore.mirrorContext(ctx, mem);
        String md = fanMarkdown(ctx);
        String speech = mem.isEmpty()
                ? "Pas encore de mémoire fan. Dis-moi un avis ou un pronostic."
                : "Mémoire fan : " + mem.summaryLine() + ".";
        cb.onSuccess(ToolResult.text(speech, md));
    }

    private static void doRemember(Context ctx, String text, ToolCallback cb) throws Exception {
        if (TextUtils.isEmpty(text)) {
            cb.onError("Précise l'avis à retenir (paramètre text).");
            return;
        }
        WeekendSnapshot snap = F1CompanionStore.load(ctx);
        // Préférence durable vs avis lié au GP
        String low = text.toLowerCase(java.util.Locale.ROOT);
        if (low.startsWith("je préfère") || low.startsWith("je prefere")
                || low.startsWith("j'aime") || low.startsWith("jaime")
                || low.contains("toujours") || low.contains("jamais")) {
            F1MemoryStore.addNote(ctx, text);
            cb.onSuccess(ToolResult.text("C’est noté dans tes préférences F1.",
                    fanMarkdown(ctx)));
            return;
        }
        F1MemoryStore.addTake(ctx, text, snap);
        cb.onSuccess(ToolResult.text("Avis retenu"
                        + (snap != null && snap.event != null ? " pour " + snap.event : "")
                        + ".",
                fanMarkdown(ctx)));
    }

    private static void doPredict(Context ctx, String text, ToolCallback cb) throws Exception {
        if (TextUtils.isEmpty(text)) {
            cb.onError("Précise le pronostic (paramètre text).");
            return;
        }
        WeekendSnapshot snap = F1CompanionStore.load(ctx);
        F1MemoryStore.addPrediction(ctx, text, snap);
        cb.onSuccess(ToolResult.text(
                "Pronostic enregistré. On verra après la course.",
                fanMarkdown(ctx)));
    }

    private static void doDebrief(Context ctx, String mode, ToolCallback cb) throws Exception {
        WeekendSnapshot snap = F1CompanionStore.ensureFresh(ctx, false);
        ContextualFileStore.getInstance(ctx).load(F1CompanionStore.CONTEXT_KEYWORD);
        F1MemoryStore.loadIntoPrompt(ctx);
        F1MemoryStore.resolveAgainstRace(ctx, snap);
        String speech = DebriefBuilder.quickSpeech(snap);
        if ("deep".equals(mode)) {
            speech = "Débrief approfondi prêt pour " + snap.event + ". "
                    + (TextUtils.isEmpty(snap.podiumLine()) ? "" : "Podium : " + snap.podiumLine() + ". ")
                    + "Tu veux qu'on commence par la stratégie, le podium, ou un de tes pronostics ?";
        }
        cb.onSuccess(ToolResult.text(speech, synthesis(ctx, snap, mode)));
    }

    private static String synthesis(Context ctx, WeekendSnapshot snap, String mode) {
        return DebriefBuilder.buildSynthesisPrompt(
                snap, mode,
                F1MemoryStore.load(ctx),
                FavoriteTeamsStore.selectedTeams(ctx));
    }

    private static String fanMarkdown(Context ctx) {
        return F1MemoryStore.load(ctx).toMarkdown(FavoriteTeamsStore.selectedTeams(ctx));
    }
}
