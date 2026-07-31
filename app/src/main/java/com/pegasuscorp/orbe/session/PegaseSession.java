package com.pegasuscorp.orbe.session;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.pegasuscorp.orbe.bureau.BureauChatStore;
import com.pegasuscorp.orbe.bureau.BureauMarkdownBrain;
import com.pegasuscorp.orbe.bureau.BureauMarkdownParser;
import com.pegasuscorp.orbe.bureau.BureauPlanningBrain;
import com.pegasuscorp.orbe.bureau.BureauProject;
import com.pegasuscorp.orbe.chat.AgenticChain;
import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.chat.ChatSendOptions;
import com.pegasuscorp.orbe.chat.ChatSessionRegistry;
import com.pegasuscorp.orbe.chat.ChatSpokenErrors;
import com.pegasuscorp.orbe.chat.CloudModelStore;
import com.pegasuscorp.orbe.chat.ConversationManager;
import com.pegasuscorp.orbe.chat.LlmReply;
import com.pegasuscorp.orbe.chat.NativeToolCall;
import com.pegasuscorp.orbe.chat.ToolSuccessHint;
import com.pegasuscorp.orbe.diag.ReasoningCard;
import com.pegasuscorp.orbe.diag.ReasoningStore;
import com.pegasuscorp.orbe.diag.ReasoningTurnCollector;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.memory.ContextAnalyzer;
import com.pegasuscorp.orbe.memory.IntentDetector;
import com.pegasuscorp.orbe.memory.ContextBuilder;
import com.pegasuscorp.orbe.memory.ContextIntent;
import com.pegasuscorp.orbe.memory.ContextSnapshot;
import com.pegasuscorp.orbe.orion.OrionPromptRewriter;
import com.pegasuscorp.orbe.orion.CodeLearnStore;
import com.pegasuscorp.orbe.orion.TaskRisk;
import com.pegasuscorp.orbe.orion.prompt.PromptCompiler;
import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;
import com.pegasuscorp.orbe.orion.qa.OrionQaChecker;
import com.pegasuscorp.orbe.orion.search.FileLocation;
import com.pegasuscorp.orbe.tools.knowledge.BriefTool;
import com.pegasuscorp.orbe.tools.knowledge.DiagTool;
import com.pegasuscorp.orbe.tools.device.MathCalcTrigger;
import com.pegasuscorp.orbe.tools.EmptyToolParams;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolDispatcher;
import com.pegasuscorp.orbe.tools.ToolRegistry;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.voice.LockSessionPolicy;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;
import com.pegasuscorp.orbe.voice.handlers.SystemIntentHandler;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Session conversationnelle partagée — voix et texte via {@link ChatSessionRegistry}.
 */
public class PegaseSession {

    private static final Object LOCK = new Object();
    private static PegaseSession instance;
    private static long nextRequestId = 1L;

    /** Timeout LLM bureau (Markdown + canvas). */
    public static final long BUREAU_LLM_TIMEOUT_SEC = 15;

    private final Context appContext;
    private ConversationManager conversationOverride;
    private final ToolRegistry toolRegistry;
    private final ToolDispatcher toolDispatcher;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<SessionObserver> observers = new CopyOnWriteArrayList<>();

    private SessionContext sessionContext = new SessionContext(Channel.TEXT, false);

    /** Contexte du tour en cours — boucle agentique après tool_calls natif. */
    private LlmReply pendingAssistantToolReply;
    private NativeToolCall pendingNativeToolCall;
    private AgenticChain activeAgenticChain;
    private ChatSendOptions activeAgenticOptions;
    private long activeRequestId;
    private int agenticStepIndex;
    /** Invalide les callbacks agentiques après {@link #clearAgenticState()}. */
    private long agenticOperationGeneration;
    /** Collecte outils / sources pour la ReasoningCard du tour. */
    private ReasoningTurnCollector turnReasoning;
    /** Intent du tour — une seule analyse + un seul {@code routing_match} par message user. */
    private ContextIntent currentTurnIntent;

    /**
     * FileLocation du dernier {@link #rewriteOrionPrompt} (phase plan).
     * À propager vers OrionPromptBuilder — ne pas re-résoudre à l'exécution.
     */
    private volatile FileLocation lastOrionPlanFileLocation;

    private PegaseSession(Context context) {
        appContext = context.getApplicationContext();
        toolRegistry = new ToolRegistry();
        toolDispatcher = new ToolDispatcher(toolRegistry);
    }

    /** Tests : session isolée avec {@link ConversationManager} injecté. */
    PegaseSession(Context context, ConversationManager conversation, ToolRegistry registry) {
        appContext = context != null ? context.getApplicationContext() : null;
        conversationOverride = conversation;
        toolRegistry = registry != null ? registry : new ToolRegistry();
        toolDispatcher = new ToolDispatcher(toolRegistry);
    }

    public static PegaseSession get(Context ctx) {
        synchronized (LOCK) {
            if (instance == null) {
                instance = new PegaseSession(ctx);
            }
            return instance;
        }
    }

    public static void reset(Context ctx) {
        synchronized (LOCK) {
            instance = null;
            ReasoningStore.clear();
            if (ctx != null) {
                ChatSessionRegistry.recreate(ctx);
            }
        }
    }

    public void init(SessionContext ctx) {
        if (ctx != null) {
            sessionContext = ctx;
        }
    }

    public void addObserver(SessionObserver obs) {
        if (obs != null && !observers.contains(obs)) {
            observers.add(obs);
        }
    }

    public void removeObserver(SessionObserver obs) {
        observers.remove(obs);
    }

    public ConversationManager getConversation() {
        return conversation();
    }

    public void enter() {
        conversation().enter();
    }

    public boolean exit() {
        return conversation().exit();
    }

    public boolean isActive() {
        return conversation().isActive();
    }

    public ConversationManager recreate(Context ctx) {
        if (conversationOverride != null) {
            return conversationOverride;
        }
        Context use = ctx != null ? ctx : appContext;
        return ChatSessionRegistry.recreate(use);
    }

    public void send(String userMessage, SessionObserver obs) {
        send(userMessage, userMessage, obs);
    }

    public void send(String payload, String displayText, SessionObserver obs) {
        if (payload == null || payload.trim().isEmpty()) {
            notifyError(obs, "Message vide.");
            return;
        }
        ConversationManager conv = conversation();
        ensureEntered(conv);

        // Confirm / choix outil → réponse dans la discussion (pas de popup système)
        if (PendingToolConfirm.hasPending()) {
            String userVisible = displayText != null ? displayText : payload;
            conv.recordUserMessage(userVisible);
            if (PendingToolConfirm.tryResolve(userVisible)) {
                notifyReply(obs, "", false);
                return;
            }
            if (PendingToolConfirm.isChoice()) {
                String again = PendingToolConfirm.question();
                String remind = again != null ? again
                        : "Dis le numéro de l'option, ou annule.";
                conv.recordToolReply(remind);
                notifyReply(obs, remind, false);
                return;
            }
            String q = PendingToolConfirm.question();
            String remind = "Je n'ai pas bien compris. "
                    + (q != null ? q : "Confirme ?")
                    + " Dis oui, non, ou annule.";
            conv.recordToolReply(remind);
            notifyReply(obs, remind, false);
            return;
        }

        clearAgenticState();
        activeRequestId = nextRequestId++;
        String userVisible = displayText != null ? displayText : payload;
        beginTurnReasoning(userVisible);
        traceUserIngress(userVisible);

        if (MathCalcTrigger.matches(userVisible)
                || MathCalcTrigger.matches(payload)) {
            dispatchDeterministicCalc(conv, userVisible, obs);
            return;
        }

        // « plus de détail » après un brief → cache local, 0 appel LLM / brief()
        if (tryBriefDetailShortCircuit(conv, userVisible, obs)) {
            return;
        }

        // Questions diag / « as-tu appelé diag ? » → local, sans LLM (évite inventer un quota)
        if (tryDiagLocalShortCircuit(conv, userVisible, obs)) {
            return;
        }

        // Batterie / heure / date → device local (évite prose LLM après un device({}) raté)
        if (tryDeviceLocalShortCircuit(conv, userVisible, obs)) {
            return;
        }

        ChatSendOptions sendOptions = buildSendOptions(userVisible);

        notifyLlmStart(obs);

        boolean stream = sessionContext.streamingEnabled && conv.supportsStreaming()
                && !sendOptions.nativeTools;
        if (stream) {
            conv.send(payload, displayText, new ChatBackend.StreamOnReply() {
                @Override
                public void onPartial(String accumulated) {
                    main.post(() -> notifyPartial(obs, accumulated));
                }

                @Override
                public void onReply(String reply) {
                    main.post(() -> handleLlmReply(conv, LlmReply.text(reply), obs));
                }

                @Override
                public void onError(String error) {
                    main.post(() -> handleLlmTransportError(conv, error, obs));
                }
            }, sendOptions);
        } else {
            conv.send(payload, displayText, new ChatBackend.OnReply() {
                @Override
                public void onReply(String reply) {
                    main.post(() -> handleLlmReply(conv, LlmReply.text(reply), obs));
                }

                @Override
                public void onLlmReply(LlmReply reply) {
                    main.post(() -> handleLlmReply(conv, reply, obs));
                }

                @Override
                public void onError(String error) {
                    main.post(() -> handleLlmTransportError(conv, error, obs));
                }
            }, sendOptions);
        }
    }

    /**
     * Édition collaborative Markdown — via le backend PegaseSession, sans polluer l'historique chat.
     */
    public void editBureauMarkdown(String documentMarkdown, String userRequest,
            BureauMarkdownBrain.Callback callback) {
        editBureauMarkdown(documentMarkdown, userRequest, callback, null);
    }

    /**
     * @param maxTokensOverride null = défaut canal BUREAU ; ex. cahier de conception = 4000
     */
    public void editBureauMarkdown(String documentMarkdown, String userRequest,
            BureauMarkdownBrain.Callback callback, Integer maxTokensOverride) {
        if (callback == null) return;
        String req = userRequest == null ? "" : userRequest.trim();
        if (req.isEmpty()) {
            main.post(() -> callback.onError("Demande vide."));
            return;
        }
        traceUserIngress(req);
        clearAgenticState();
        final String doc = documentMarkdown == null ? "" : documentMarkdown;
        final boolean questionMode = BureauMarkdownBrain.isQuestion(req);
        BureauMarkdownBrain.BuiltPrompt built =
                BureauMarkdownBrain.buildPromptMarkdown(appContext, doc, req);
        Trace.bureauAction("llm_request",
                questionMode ? "question" : "edit",
                built.contextChunks);
        final int contextChunks = built.contextChunks;
        String prompt = built.text;
        notifyLlmStart(null);
        ChatSendOptions opts = ChatSendOptions.legacy(Channel.BUREAU);
        if (maxTokensOverride != null && maxTokensOverride > 0) {
            opts = opts.withMaxTokens(maxTokensOverride);
        }
        conversation().completeEphemeral(prompt, new ChatBackend.OnReply() {
            @Override
            public void onLlmReply(LlmReply reply) {
                deliverBureauMarkdown(callback, doc, req, questionMode, contextChunks,
                        reply.content != null ? reply.content : "", false);
            }

            @Override
            public void onReply(String text) {
                deliverBureauMarkdown(callback, doc, req, questionMode, contextChunks,
                        text, false);
            }

            @Override
            public void onError(String error) {
                deliverBureauMarkdownFallback(callback, doc, req, contextChunks, error);
            }
        }, opts, channelTraceSource());
    }

    /**
     * Q&A fil Pégase bureau — historique {@link BureauChatStore}, hors chat voix/texte.
     */
    public void completeBureauThread(String documentMarkdown, String userMessage,
            List<BureauChatStore.Turn> bureauTurns, ChatBackend.OnReply callback) {
        if (callback == null) return;
        String req = userMessage == null ? "" : userMessage.trim();
        if (req.isEmpty()) {
            main.post(() -> callback.onError("Message vide."));
            return;
        }
        traceUserIngress(req);
        clearAgenticState();
        final String doc = documentMarkdown == null ? "" : documentMarkdown;
        BureauMarkdownBrain.BuiltPrompt built =
                BureauMarkdownBrain.buildThreadPrompt(appContext, doc, req);
        Trace.bureauAction("llm_request", "thread", built.contextChunks);
        List<ChatBackend.Turn> hist = new ArrayList<>();
        if (bureauTurns != null) {
            // Les derniers tours seulement (le prompt courant porte le doc)
            int start = Math.max(0, bureauTurns.size() - 12);
            for (int i = start; i < bureauTurns.size(); i++) {
                BureauChatStore.Turn t = bureauTurns.get(i);
                if (t == null || t.text == null || t.text.trim().isEmpty()) continue;
                hist.add(new ChatBackend.Turn(t.fromUser, t.text));
            }
        }
        notifyLlmStart(null);
        conversation().completeBureauThread(hist, built.text, new ChatBackend.OnReply() {
            @Override
            public void onLlmReply(LlmReply reply) {
                callback.onLlmReply(reply);
            }

            @Override
            public void onReply(String text) {
                callback.onReply(text);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        }, ChatSendOptions.legacy(Channel.BUREAU), channelTraceSource());
    }

    /**
     * Tour d'interview planification Bureau (éphémère, hors historique chat).
     */
    public void completeBureauPlanningTurn(String titleHint,
            List<BureauChatStore.Turn> turns, String userMessage,
            ChatBackend.OnReply callback) {
        if (callback == null) return;
        String req = userMessage == null ? "" : userMessage.trim();
        if (req.isEmpty()) {
            main.post(() -> callback.onError("Message vide."));
            return;
        }
        traceUserIngress(req);
        clearAgenticState();
        String prompt = BureauPlanningBrain.buildInterviewPrompt(titleHint, turns, req);
        Trace.bureauAction("llm_request", "planning_turn", 0);
        notifyLlmStart(null);
        conversation().completeEphemeral(prompt, new ChatBackend.OnReply() {
            @Override
            public void onLlmReply(LlmReply reply) {
                callback.onLlmReply(reply);
            }

            @Override
            public void onReply(String text) {
                callback.onReply(text);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        }, ChatSendOptions.legacy(Channel.BUREAU), channelTraceSource());
    }

    /**
     * Matérialise un projet JSON depuis le transcript d'interview.
     */
    public void completeBureauPlanningMaterialize(String titleHint,
            List<BureauChatStore.Turn> turns, ChatBackend.OnReply callback) {
        if (callback == null) return;
        traceUserIngress("materialize:" + (titleHint == null ? "" : titleHint));
        clearAgenticState();
        String prompt = BureauPlanningBrain.buildMaterializePrompt(titleHint, turns);
        Trace.bureauAction("llm_request", "planning_materialize", 0);
        notifyLlmStart(null);
        conversation().completeEphemeral(prompt, new ChatBackend.OnReply() {
            @Override
            public void onLlmReply(LlmReply reply) {
                callback.onLlmReply(reply);
            }

            @Override
            public void onReply(String text) {
                callback.onReply(text);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        }, ChatSendOptions.legacy(Channel.BUREAU), channelTraceSource());
    }

    /**
     * Édition projet structuré — le LLM produit des COMMANDS, pas du Markdown.
     */
    public void completeBureauProjectCommands(BureauProject project, String userRequest,
            ChatBackend.OnReply callback) {
        if (callback == null) return;
        String req = userRequest == null ? "" : userRequest.trim();
        if (req.isEmpty()) {
            main.post(() -> callback.onError("Demande vide."));
            return;
        }
        if (project == null) {
            main.post(() -> callback.onError("Projet introuvable."));
            return;
        }
        traceUserIngress(req);
        clearAgenticState();
        String prompt = BureauPlanningBrain.buildCommandsPrompt(project, req);
        Trace.bureauAction("llm_request", "project_commands", 0);
        notifyLlmStart(null);
        conversation().completeEphemeral(prompt, new ChatBackend.OnReply() {
            @Override
            public void onLlmReply(LlmReply reply) {
                callback.onLlmReply(reply);
            }

            @Override
            public void onReply(String text) {
                callback.onReply(text);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        }, ChatSendOptions.legacy(Channel.BUREAU), channelTraceSource());
    }

    /**
     * Appel LLM bureau synchrone (canvas {@link com.pegasuscorp.orbe.bureau.BureauBrain}).
     */
    public String completeBureauSync(String prompt) throws Exception {
        traceUserIngress(truncateForTrace(prompt));
        return conversation().completeEphemeralSync(prompt, BUREAU_LLM_TIMEOUT_SEC,
                ChatSendOptions.legacy(Channel.BUREAU), channelTraceSource());
    }

    /**
     * Analyse QA diag — canal TEXT, 800 tokens, hors historique chat.
     */
    public String completeDiagAnalyzeSync(String prompt) throws Exception {
        traceUserIngress(truncateForTrace(prompt));
        return conversation().completeEphemeralSync(prompt, 60,
                ChatSendOptions.legacy(Channel.TEXT).withMaxTokens(800), "diag");
    }

    /**
     * PromptCompiler : questions de précision (apprentissage code) ou Mission pour Orion.
     * @param priorQa précisions déjà fournies (2ᵉ passe), peut être null
     */
    public void rewriteOrionPrompt(String rawUserPrompt, String priorQa,
            ChatBackend.OnReply callback) {
        if (callback == null) return;
        String raw = rawUserPrompt == null ? "" : rawUserPrompt.trim();
        if (raw.isEmpty()) {
            main.post(() -> callback.onError("Écris d'abord ta demande à Orion."));
            return;
        }
        boolean learn = CodeLearnStore.isEnabled(appContext);
        String hint = OrionPromptRewriter.projectHint(appContext);
        String learned = CodeLearnStore.relevantHint(appContext, raw);
        String qa = priorQa == null ? "" : priorQa.trim();
        // 2ᵉ passe avec réponses → forcer Mission
        boolean askPhase = learn && qa.isEmpty();
        FileLocation location = PromptCompiler.findFileLocationForDemand(appContext, raw);
        lastOrionPlanFileLocation = location;
        String meta = OrionPromptRewriter.buildMetaPrompt(
                raw, hint, askPhase, qa, learned, location);
        int snippetTokens = location != null
                ? Math.max(1, location.toPromptBlock().length() / 4) : 0;
        Trace.orionSandwich("plan", snippetTokens, location != null, "READY");
        traceUserIngress(truncateForTrace(raw));
        clearAgenticState();
        notifyLlmStart(null);
        conversation().completeEphemeral(meta, new ChatBackend.OnReply() {
            @Override
            public void onLlmReply(LlmReply reply) {
                String cleaned = OrionPromptRewriter.cleanRewritten(
                        reply != null && reply.content != null ? reply.content : "");
                if (cleaned.isEmpty()) {
                    main.post(() -> callback.onError("Pégase n'a rien renvoyé."));
                    return;
                }
                callback.onLlmReply(LlmReply.text(cleaned));
            }

            @Override
            public void onReply(String text) {
                String cleaned = OrionPromptRewriter.cleanRewritten(text);
                if (cleaned.isEmpty()) {
                    main.post(() -> callback.onError("Pégase n'a rien renvoyé."));
                    return;
                }
                callback.onReply(cleaned);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        }, ChatSendOptions.legacy(Channel.ORION).withMaxTokens(1100), "orion_rewrite");
    }

    /** Compat : 1ʳᵉ passe sans précisions. */
    public void rewriteOrionPrompt(String rawUserPrompt, ChatBackend.OnReply callback) {
        rewriteOrionPrompt(rawUserPrompt, null, callback);
    }

    /**
     * Emplacement trouvé à la phase plan (rewrite). Peut être null.
     * OrionStreamView le passe à {@code OrionPromptBuilder} sans re-search.
     */
    public FileLocation getLastOrionPlanFileLocation() {
        return lastOrionPlanFileLocation;
    }

    /**
     * QA sémantique Orion — CONFORME / NON_CONFORME (éphémère, hors historique).
     * {@code missionBlock} peut déjà être le prompt de vérification complet (Phase 6).
     */
    public String completeOrionQaSync(String missionBlock, String diffSummary) throws Exception {
        String prompt;
        if (TextUtils.isEmpty(diffSummary) && !TextUtils.isEmpty(missionBlock)
                && missionBlock.contains("=== Diff généré par Orion ===")) {
            prompt = missionBlock;
        } else {
            prompt = OrionQaChecker.buildSemanticPrompt(missionBlock, diffSummary);
        }
        traceUserIngress(truncateForTrace("orion_qa"));
        return conversation().completeEphemeralSync(prompt, 45,
                ChatSendOptions.legacy(Channel.ORION).withMaxTokens(400), "orion_qa");
    }

    /** QA avec tâche enrichie (snippet + risque). */
    public String completeOrionQaSync(ResolvedTask task, String diffSummary) throws Exception {
        boolean full = task == null || task.risk == null
                || task.risk == TaskRisk.HIGH || task.risk == TaskRisk.CRITICAL;
        String prompt = OrionQaChecker.buildVerificationPrompt(task, diffSummary, full);
        traceUserIngress(truncateForTrace("orion_qa"));
        return conversation().completeEphemeralSync(prompt, 45,
                ChatSendOptions.legacy(Channel.ORION).withMaxTokens(
                        full ? 400 : 250), "orion_qa");
    }

    /** Découpage mission LARGE — JSON chunks (éphémère, hors historique). */
    public String completeOrionChunkSync(String decompositionPrompt) throws Exception {
        traceUserIngress(truncateForTrace("orion_chunk"));
        return conversation().completeEphemeralSync(decompositionPrompt, 60,
                ChatSendOptions.legacy(Channel.ORION).withMaxTokens(800), "orion_chunk");
    }

    /** Plan mission MASSIVE — JSON étapes (éphémère, hors historique). */
    public String completeOrionPlanSync(String planPrompt) throws Exception {
        traceUserIngress(truncateForTrace("orion_plan"));
        return conversation().completeEphemeralSync(planPrompt, 60,
                ChatSendOptions.legacy(Channel.ORION).withMaxTokens(1000), "orion_plan");
    }

    /** Planification cachée copilote — éphémère, hors historique (P3 v3). */
    public String completeCopilotReflectionSync(String reflectionPrompt) throws Exception {
        return conversation().completeEphemeralSync(reflectionPrompt, 30,
                ChatSendOptions.legacy(Channel.TEXT).withMaxTokens(350), "copilot_reflection");
    }

    public Channel getChannel() {
        return sessionContext.channel;
    }

    private void deliverBureauMarkdown(BureauMarkdownBrain.Callback callback, String document,
            String userRequest, boolean questionMode, int contextChunks,
            String raw, boolean fallback) {
        String text = raw == null ? "" : raw;
        boolean tableMode = !questionMode && BureauMarkdownBrain.wantsMarkdownTable(userRequest);
        boolean mermaidMode = !questionMode && BureauMarkdownBrain.wantsMermaid(userRequest);
        if (tableMode && !BureauMarkdownBrain.hasMarkdownTableSeparator(text)) {
            // Tableau mal formé (pas de |---|) → insérer la ligne de séparation
            text = BureauMarkdownBrain.ensureMarkdownTableSeparators(text);
            android.util.Log.d("BUREAU", "table_separator_fixed="
                    + BureauMarkdownBrain.hasMarkdownTableSeparator(text));
        }
        if (mermaidMode) {
            text = BureauMarkdownBrain.ensureMermaidFence(text);
            android.util.Log.d("BUREAU", "mermaid_fence="
                    + BureauMarkdownBrain.hasMermaidFence(text));
        }
        if (!fallback && !questionMode && ToolDispatcher.isToolCall(text)) {
            resolveBureauToolCall(callback, document, userRequest, false, contextChunks, text);
            return;
        }
        postBureauMarkdownResult(callback, document, userRequest, questionMode,
                contextChunks, text, fallback);
    }

    /**
     * Si le LLM a émis un JSON d'outil (ex. calculator), l'exécute et remplace
     * le JSON par le résultat avant écriture dans le .md.
     */
    private void resolveBureauToolCall(BureauMarkdownBrain.Callback callback, String document,
            String userRequest, boolean questionMode, int contextChunks, String raw) {
        final String toolId = ToolDispatcher.guessToolId(raw);
        notifyToolStart(null, toolId != null ? toolId : "outil");
        toolDispatcher.dispatch(appContext, raw, new ToolCallback() {
            @Override
            public void onSuccess(ToolResult result) {
                notifyToolEnd(null, toolId != null ? toolId : "outil", true);
                postBureauMarkdownResult(callback, document, userRequest, questionMode,
                        contextChunks,
                        BureauMarkdownBrain.materializeToolResult(raw, result), false);
            }

            @Override
            public void onSuccessAndExit(ToolResult result) {
                onSuccess(result);
            }

            @Override
            public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                // Bureau : pas de confirmation interactive — on annule le JSON brut
                if (onCancel != null) onCancel.run();
                notifyToolEnd(null, toolId != null ? toolId : "outil", false);
                postBureauMarkdownResult(callback, document, userRequest, questionMode,
                        contextChunks,
                        BureauMarkdownBrain.materializeToolError(raw,
                                "Confirmation requise — outil non exécuté."),
                        false);
            }

            @Override
            public void onError(String error) {
                notifyToolEnd(null, toolId != null ? toolId : "outil", false);
                postBureauMarkdownResult(callback, document, userRequest, questionMode,
                        contextChunks,
                        BureauMarkdownBrain.materializeToolError(raw, error), false);
            }
        });
    }

    private void postBureauMarkdownResult(BureauMarkdownBrain.Callback callback, String document,
            String userRequest, boolean questionMode, int contextChunks,
            String raw, boolean fallback) {
        main.post(() -> {
            BureauMarkdownParser.Parsed parsed = BureauMarkdownParser.parse(raw, document);
            if (questionMode) {
                parsed = BureauMarkdownBrain.finalizeQuestionReply(document, userRequest, parsed);
            }
            boolean halluc = questionMode
                    && BureauMarkdownBrain.isPotentialHallucination(
                            parsed.markdown != null ? parsed.markdown : raw, contextChunks);
            Trace.bureauEditResult(fallback, parsed.replaceAll, parsed.markdown.length(),
                    parsed.speak, halluc);
            callback.onResult(new BureauMarkdownBrain.Result(parsed, raw));
        });
    }

    private void deliverBureauMarkdownFallback(BureauMarkdownBrain.Callback callback,
            String document, String userRequest, int contextChunks, String error) {
        main.post(() -> {
            android.util.Log.w("PegaseSession", "Bureau LLM fallback: " + error);
            BureauMarkdownParser.Parsed fallback =
                    BureauMarkdownBrain.localFallback(userRequest);
            boolean questionMode = BureauMarkdownBrain.isQuestion(userRequest);
            if (questionMode) {
                fallback = BureauMarkdownBrain.finalizeQuestionReply(
                        document, userRequest, fallback);
            }
            boolean halluc = questionMode
                    && BureauMarkdownBrain.isPotentialHallucination(
                            fallback.markdown, contextChunks);
            Trace.bureauEditResult(true, fallback.replaceAll, fallback.markdown.length(),
                    fallback.speak, halluc);
            callback.onResult(new BureauMarkdownBrain.Result(fallback, ""));
        });
    }

    private static String truncateForTrace(String prompt) {
        if (prompt == null) return "";
        int idx = prompt.indexOf("=== DEMANDE ===");
        if (idx >= 0) {
            String tail = prompt.substring(idx);
            return tail.length() > 120 ? tail.substring(0, 120) + "…" : tail;
        }
        return prompt.length() > 80 ? prompt.substring(0, 80) + "…" : prompt;
    }

    /**
     * Après un brief récent, « plus de détail » sert le cache — sans LLM ni brief().
     */
    private boolean tryBriefDetailShortCircuit(ConversationManager conv, String userText,
            SessionObserver obs) {
        if (userText == null || !BriefTool.hasRecentBrief(appContext)) return false;
        String fold = SpeechInputNormalizer.fold(userText).replace('\'', ' ');
        if (!BriefTool.looksLikeBriefDetailFollowUp(fold)) return false;
        String detail = BriefTool.composeBriefDetail(appContext);
        if (detail == null || detail.trim().isEmpty()) return false;
        conv.addUserMessage(userText.trim());
        if (turnReasoning != null) {
            turnReasoning.noteToolStart("brief", new JSONObject());
            turnReasoning.noteToolEnd("brief", true, 0, "cache local — plus de détail");
        }
        conv.recordToolReply(detail.trim());
        publishReasoningForReply(detail.trim());
        notifyReply(obs, detail.trim(), false);
        return true;
    }

    /**
     * Court-circuit diag local : réponse honnête depuis la trace, ou lancement de l'outil.
     * Évite que le LLM invente « j'ai essayé mais quota dépassé ».
     */
    private boolean tryDeviceLocalShortCircuit(ConversationManager conv, String userText,
            SessionObserver obs) {
        if (userText == null || userText.trim().isEmpty()) return false;
        String fold = SpeechInputNormalizer.fold(userText).replace('\'', ' ')
                .replace('’', ' ').replaceAll("\\s+", " ").trim();
        if (!SystemIntentHandler.looksLikeDevice(fold)) return false;
        if (!allowToolExecution(obs)) {
            conv.addUserMessage(userText.trim());
            conv.recordToolReply(LockSessionPolicy.UNLOCK_TOOL_MESSAGE);
            notifyToolBlocked(obs);
            return true;
        }
        try {
            JSONObject params = new JSONObject()
                    .put("action", SystemIntentHandler.deviceActionFromFold(fold));
            executeToolInternal("device", params, userText.trim(), obs, true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tryDiagLocalShortCircuit(ConversationManager conv, String userText,
            SessionObserver obs) {
        if (userText == null || userText.trim().isEmpty()) return false;
        String fold = SpeechInputNormalizer.fold(userText).replace('\'', ' ')
                .replace('’', ' ').replaceAll("\\s+", " ").trim();
        if (IntentDetector.looksLikeDiagToolUsageQuestion(fold)) {
            String answer = answerDiagToolUsageFromTrace();
            conv.addUserMessage(userText.trim());
            if (turnReasoning != null) {
                turnReasoning.noteToolStart("diag", new JSONObject());
                turnReasoning.noteToolEnd("diag", true, 0, "lecture trace locale");
            }
            conv.recordToolReply(answer);
            publishReasoningForReply(answer);
            notifyReply(obs, answer, false);
            return true;
        }
        // Relance après un bilan summary → detail (même sans looksLikeDiag)
        boolean deepenAfterSummary = IntentDetector.looksLikeDiagDetailFollowUp(fold)
                && DiagTool.hasRecentSummary(appContext);
        if (!deepenAfterSummary
                && !IntentDetector.looksLikeDiag(fold)
                && !IntentDetector.looksLikeDiagAnalyze(fold)) {
            return false;
        }
        // Bilan / analyse / weekly / relance → outil diag sans passer par le LLM d'abord
        if (!allowToolExecution(obs)) {
            conv.addUserMessage(userText.trim());
            conv.recordToolReply(LockSessionPolicy.UNLOCK_TOOL_MESSAGE);
            notifyToolBlocked(obs);
            return true;
        }
        try {
            JSONObject params = new JSONObject();
            if (IntentDetector.looksLikeDiagAnalyze(fold) && !deepenAfterSummary) {
                params.put("action", "analyze");
            } else if (IntentDetector.looksLikeWeeklyDiag(fold) && !deepenAfterSummary) {
                params.put("action", "weekly");
            } else if (IntentDetector.looksLikeDiagSearch(fold) && !deepenAfterSummary) {
                params.put("action", "search");
                params.put("query", userText.trim());
            } else if (deepenAfterSummary
                    || IntentDetector.looksLikeDiagDetail(fold)) {
                params.put("action", "detail");
                params.put("utterance", userText.trim());
                if (deepenAfterSummary) {
                    String day = DiagTool.recentSummaryDayIso(appContext);
                    if (day != null && !day.isEmpty()) params.put("date", day);
                }
            } else {
                params.put("action", "summary");
            }
            executeToolInternal("diag", params, userText.trim(), obs, true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Lit la trace : y a-t-il eu un tool_start diag récemment ? */
    private String answerDiagToolUsageFromTrace() {
        try {
            if (appContext != null) Trace.init(appContext);
            java.io.File f = Trace.file();
            if (f == null || !f.isFile()) {
                return "Non — je n'ai pas d'appel récent à l'outil diag dans mes traces. "
                        + "Tu veux que je lance un bilan maintenant ?";
            }
            boolean found = false;
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(f),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("\"tool\":\"diag\"")
                            || line.contains("\"tool\": \"diag\"")) {
                        if (line.contains("tool_start") || line.contains("tool_end")) {
                            found = true;
                        }
                    }
                }
            }
            if (found) {
                return "Oui — j'ai bien enregistré au moins un appel à l'outil diag dans mes traces. "
                        + "Dis « analyse tes problèmes » ou « bilan de session » pour le détail.";
            }
            return "Non — sur ta dernière demande, je n'ai pas appelé l'outil diag. "
                    + "Je peux le faire maintenant : dis « bilan de session » ou "
                    + "« analyse tes problèmes ».";
        } catch (Exception e) {
            return "Je n'ai pas pu vérifier la trace localement. "
                    + "Dis « bilan de session » pour forcer un diagnostic.";
        }
    }

    /**
     * Chiffres + signes maths → calculator déterministe, réponse locale sans LLM.
     */
    private void dispatchDeterministicCalc(ConversationManager conv, String userText,
            SessionObserver obs) {
        if (!allowToolExecution(obs)) {
            conv.addUserMessage(userText);
            conv.recordToolReply(LockSessionPolicy.UNLOCK_TOOL_MESSAGE);
            notifyToolBlocked(obs);
            return;
        }
        try {
            JSONObject params = new JSONObject()
                    .put("expression", userText);
            conv.addUserMessage(userText.trim());
            beginTurnReasoning(userText.trim());
            Trace.toolStart("calculator", params, true);
            notifyToolStart(obs, "calculator");
            if (turnReasoning != null) {
                turnReasoning.noteToolStart("calculator", params);
            }
            final long startedAt = System.currentTimeMillis();
            notifyReply(obs, "", true);
            Tool calculator = toolRegistry.findById("calculator");
            if (calculator == null) {
                notifyError(obs, "Calcul impossible : outil calculator introuvable.");
                return;
            }
            calculator.execute(appContext, params, new ToolCallback() {
                @Override
                public void onSuccess(ToolResult result) {
                    main.post(() -> finishDeterministicCalc(conv, startedAt, result, null, obs));
                }

                @Override
                public void onSuccessAndExit(ToolResult result) {
                    main.post(() -> finishDeterministicCalc(conv, startedAt, result, null, obs));
                }

                @Override
                public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                    main.post(() -> handleToolConfirm(obs, question, onConfirm, onCancel));
                }

                @Override
                public void onChoiceNeeded(String title, String[] labels,
                        java.util.function.IntConsumer onChosen, Runnable onCancel) {
                    main.post(() -> handleToolChoice(obs, title, labels, onChosen, onCancel));
                }

                @Override
                public void onProgress(String message) {
                    if (message != null && !message.isEmpty()) {
                        notifyPartial(obs, message);
                    }
                }

                @Override
                public void onError(String error) {
                    main.post(() -> finishDeterministicCalc(conv, startedAt, null, error, obs));
                }
            });
        } catch (Exception e) {
            notifyError(obs, "Calcul impossible : " + e.getMessage());
        }
    }

    private void finishDeterministicCalc(ConversationManager conv, long startedAt,
            ToolResult result, String error, SessionObserver obs) {
        long duration = System.currentTimeMillis() - startedAt;
        if (error != null) {
            if (turnReasoning != null) {
                turnReasoning.noteToolEnd("calculator", false, duration, error);
            }
            Trace.toolEnd("calculator", false, duration, null, error);
            Trace.toolFailureContext("calculator", "tool_execute_error", error,
                    conv != null ? conv.getLastUserText() : null);
            notifyToolEnd(obs, "calculator", false);
            notifyError(obs, error);
            return;
        }
        if (turnReasoning != null) {
            String preview = result != null ? result.wireText() : "";
            if (preview == null || preview.isEmpty()) {
                preview = result != null ? result.text : "";
            }
            turnReasoning.noteToolEnd("calculator", true, duration, preview);
        }
        String text = result != null ? result.text : "";
        Trace.toolEnd("calculator", true, duration, result != null ? result.wireText() : "", null);
        notifyToolEnd(obs, "calculator", true);
        conv.recordToolReply(text);
        publishReasoningForReply(text);
        notifyReply(obs, text, false);
    }

    private ChatSendOptions buildSendOptions(String userMessage) {
        Channel channel = sessionContext.channel;
        ContextIntent intent = currentTurnIntent;
        if (intent == null) {
            intent = ContextAnalyzer.analyze(appContext, userMessage);
        }
        if (!useNativeFunctionCalling()) {
            return ChatSendOptions.legacy(channel).withIntent(intent);
        }
        if (channel == Channel.VOICE) {
            return ChatSendOptions.forVoice(intent.allowedTools)
                    .withIntent(intent)
                    .withVoiceTokenBudget(appContext);
        }
        return ChatSendOptions.forText(intent.allowedTools).withIntent(intent);
    }

    private boolean useNativeFunctionCalling() {
        if (sessionContext.channel != Channel.TEXT && sessionContext.channel != Channel.VOICE) {
            return false;
        }
        return CloudModelStore.supportsNativeFunctionCalling(appContext);
    }

    /**
     * Exécution directe sans LLM — trace + historique.
     * @return false si le JSON n'est pas un appel d'outil (fallback chat).
     */
    public boolean executeToolFromJson(String toolJson, String userLine, SessionObserver obs) {
        if (!ToolDispatcher.isToolCall(toolJson)) {
            if (ToolDispatcher.looksLikeToolAttempt(toolJson)) {
                String toolHint = ToolDispatcher.guessToolId(toolJson);
                Trace.toolHesitation(toolHint, "malformed_tool",
                        "JSON d'outil invalide (executeToolFromJson)",
                        userLine);
                notifyError(obs, "Je n'ai pas pu exécuter l'outil — le format était incorrect.");
                return true;
            }
            return false;
        }

        String cleaned = ToolDispatcher.extractJson(toolJson);
        JSONObject json;
        try {
            json = new JSONObject(cleaned);
        } catch (Exception e) {
            notifyError(obs, "Format d'outil invalide — JSON illisible.");
            return true;
        }

        String toolId = json.optString("tool", "").trim();
        if (toolId.isEmpty()) {
            notifyError(obs, "Format d'outil invalide — identifiant manquant.");
            return true;
        }

        JSONObject params = json.optJSONObject("params");
        if (params == null) params = new JSONObject();
        executeToolInternal(toolId, params, userLine, obs, true);
        return true;
    }

    public void executeTool(String toolId, JSONObject params, SessionObserver obs) {
        executeTool(toolId, params, null, obs);
    }

    /** Exécution directe sans LLM — trace + historique. */
    public void executeTool(String toolId, JSONObject params, String userLine, SessionObserver obs) {
        executeToolInternal(toolId, params, userLine, obs, true);
    }

    private void executeToolInternal(String toolId, JSONObject params, String userLine,
            SessionObserver obs, boolean localRouter) {
        if (toolId == null || toolId.trim().isEmpty()) {
            notifyError(obs, "Outil manquant.");
            return;
        }
        ConversationManager conv = conversation();
        ensureEntered(conv);
        if (userLine != null && !userLine.trim().isEmpty()) {
            conv.addUserMessage(userLine.trim());
        }
        JSONObject safeParams = params != null ? params : new JSONObject();

        Tool tool = toolRegistry.findById(toolId.trim());
        if (tool == null) {
            notifyError(obs, "Outil inconnu : " + toolId);
            return;
        }

        // LLM rappelle brief après « plus de détail » → cache, pas de 2e agrégat
        // (avant seed utterance : {} doit rester « vide » pour ce filet)
        if ("brief".equalsIgnoreCase(toolId.trim())
                && BriefTool.hasRecentBrief(appContext)) {
            String last = conv.getLastUserText();
            if (userLine != null && !userLine.trim().isEmpty()) {
                last = userLine.trim();
            }
            String fold = SpeechInputNormalizer.fold(last != null ? last : "")
                    .replace('\'', ' ');
            if (BriefTool.looksLikeBriefDetailFollowUp(fold)
                    || BriefTool.isEmptyBriefParams(safeParams)) {
                String detail = BriefTool.composeBriefDetail(appContext);
                if (detail != null && !detail.trim().isEmpty()) {
                    finishTool(conv, toolId, System.currentTimeMillis(),
                            ToolResult.text(detail.trim()), null, false, obs);
                    return;
                }
            }
        }

        String seedText = userLine != null && !userLine.trim().isEmpty()
                ? userLine.trim()
                : (conv != null ? conv.getLastUserText() : null);
        safeParams = EmptyToolParams.seedUtteranceIfEmpty(toolId.trim(), safeParams, seedText);

        // Fallback 120b/Qwen : notepad(add) avec vocabulaire diag = invention probable
        if (shouldBlockNotepadDiagFallback(appContext, toolId, safeParams)) {
            String text = safeParams.optString("text", "").trim();
            String req = (userLine != null && !userLine.trim().isEmpty())
                    ? userLine.trim()
                    : (conv != null ? conv.getLastUserText() : "");
            Trace.phantomBlocked(req, "notepad(add): " + text, "diag_fallback_blocked");
            finishTool(conv, toolId, System.currentTimeMillis(),
                    ToolResult.text("Je ne note pas ça — contenu trop proche d'un diagnostic "
                            + "inventé sur ce modèle."),
                    null, false, obs);
            return;
        }

        Trace.toolStart(toolId, safeParams, localRouter);
        notifyToolStart(obs, toolId);
        if (turnReasoning == null) {
            String seed = userLine != null && !userLine.trim().isEmpty()
                    ? userLine.trim()
                    : (conv != null ? conv.getLastUserText() : "");
            beginTurnReasoning(seed != null ? seed : "");
        }
        if (turnReasoning != null) {
            turnReasoning.noteToolStart(toolId, safeParams);
        }
        final long startedAt = System.currentTimeMillis();
        tool.execute(appContext, safeParams, new ToolCallback() {
            @Override
            public void onSuccess(ToolResult result) {
                main.post(() -> finishTool(conv, toolId, startedAt, result, null, false, obs));
            }

            @Override
            public void onSuccessAndExit(ToolResult result) {
                main.post(() -> finishTool(conv, toolId, startedAt, result, null, true, obs));
            }

            @Override
            public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                main.post(() -> handleToolConfirm(obs, question, onConfirm, onCancel));
            }

            @Override
            public void onChoiceNeeded(String title, String[] labels,
                    java.util.function.IntConsumer onChosen, Runnable onCancel) {
                main.post(() -> handleToolChoice(obs, title, labels, onChosen, onCancel));
            }

            @Override
            public void onProgress(String message) {
                if (message != null && !message.isEmpty()) {
                    notifyToolProgress(obs, message);
                }
            }

            @Override
            public void onError(String error) {
                main.post(() -> finishTool(conv, toolId, startedAt, null, error, false, obs));
            }
        });
    }

    private void handleToolConfirm(SessionObserver obs, String question,
            Runnable onConfirm, Runnable onCancel) {
        boolean handled = false;
        if (obs != null && obs.onConfirmNeeded(question, onConfirm, onCancel)) {
            handled = true;
        }
        if (!handled) {
            for (SessionObserver o : observers) {
                if (o.onConfirmNeeded(question, onConfirm, onCancel)) {
                    handled = true;
                    break;
                }
            }
        }
        if (!handled) {
            // Discussion / voix : question en bulles, pas de popup
            PendingToolConfirm.set(question, onConfirm, onCancel);
            ConversationManager conv = conversation();
            ensureEntered(conv);
            conv.recordToolReply(question);
            notifyReply(obs, question, false);
        }
    }

    private void handleToolChoice(SessionObserver obs, String title, String[] labels,
            java.util.function.IntConsumer onChosen, Runnable onCancel) {
        boolean handled = false;
        if (obs != null && obs.onChoiceNeeded(title, labels, onChosen, onCancel)) {
            handled = true;
        }
        if (!handled) {
            for (SessionObserver o : observers) {
                if (o.onChoiceNeeded(title, labels, onChosen, onCancel)) {
                    handled = true;
                    break;
                }
            }
        }
        if (!handled) {
            String prompt = PendingToolConfirm.formatChoicePrompt(title, labels);
            PendingToolConfirm.setChoice(prompt, labels, onChosen, onCancel);
            ConversationManager conv = conversation();
            ensureEntered(conv);
            conv.recordToolReply(prompt);
            notifyReply(obs, prompt, false);
        }
    }

    private void handleLlmReply(ConversationManager conv, LlmReply reply, SessionObserver obs) {
        if (reply.hasNativeToolCalls()) {
            String preview = reply.content != null ? reply.content : "";
            notifyReply(obs, preview, true);
            if (!allowToolExecution(obs)) {
                conv.recordToolReply(LockSessionPolicy.UNLOCK_TOOL_MESSAGE);
                notifyToolBlocked(obs);
                return;
            }
            NativeToolCall call = reply.toolCalls.get(0);
            pendingAssistantToolReply = reply;
            pendingNativeToolCall = call;
            executeToolInternal(call.name, call.arguments, null, obs, false);
            return;
        }

        String text = reply.content != null ? reply.content : "";
        if (ToolDispatcher.isToolCall(text)) {
            notifyReply(obs, text, true);
            if (!allowToolExecution(obs)) {
                conv.recordToolReply(LockSessionPolicy.UNLOCK_TOOL_MESSAGE);
                notifyToolBlocked(obs);
                return;
            }
            dispatchToolFromLlm(conv, text, obs);
            return;
        }
        if (ToolDispatcher.looksLikeToolAttempt(text)) {
            String toolHint = ToolDispatcher.guessToolId(text);
            Trace.toolHesitation(toolHint, "malformed_tool",
                    "JSON d'outil invalide dans la réponse LLM",
                    conv.getLastUserText());
            notifyError(obs, "Je n'ai pas pu exécuter l'outil — le format était incorrect.");
            return;
        }
        // Carte avant notify : l'UI refresh Discussion dans onReply et doit déjà trouver la carte.
        markTurnLlmSynthesis(conv);
        publishReasoningForReply(text);
        notifyReply(obs, text, false);
    }

    /**
     * Erreurs transport LLM — trace enrichie sur HTTP 400 validation d'outils.
     */
    private void handleLlmTransportError(ConversationManager conv, String error,
            SessionObserver obs) {
        // 400 tool_choice none : récupérer le dernier résultat d'outil au lieu de « Désolé »
        if (isToolChoiceConflict(error) && activeAgenticChain != null) {
            String fallback = activeAgenticChain.lastToolDisplayText();
            if (fallback != null && !fallback.trim().isEmpty()) {
                Trace.error("agentic", "tool_choice_conflict: " + error);
                finalizeAgentic(conv, obs, fallback);
                return;
            }
        }
        if (isHttp400ToolValidation(error)) {
            String tool = ToolDispatcher.guessToolId(error);
            if (tool == null || tool.isEmpty() || "unknown".equals(tool)) {
                tool = "llm";
            }
            Trace.toolFailureContext(tool, "http_400_tool_validation",
                    summarizeToolValidationError(error),
                    conv != null ? conv.getLastUserText() : null);
        }
        String spoken = ChatSpokenErrors.toUserMessage(error);
        if (turnReasoning == null) {
            beginTurnReasoning(conv != null ? conv.getLastUserText() : "");
        }
        publishReasoningForReply(spoken);
        notifyError(obs, spoken);
    }

    /** Visible tests + logs HTTP 400 validation d'outils Groq/OpenAI. */
    public static boolean isHttp400ToolValidation(String error) {
        if (error == null || error.isEmpty()) return false;
        String e = error.toLowerCase(java.util.Locale.ROOT);
        // Codes / messages Groq typiques (même sans « HTTP 400 » explicite dans le wrap)
        if (e.contains("tool_use_failed") || e.contains("tool call validation")
                || e.contains("failed to validate tool")
                || (e.contains("parameters for tool") && e.contains("did not match schema"))) {
            return true;
        }
        if (!e.contains("http 400") && !e.contains("status code 400")
                && !e.contains("\"code\":400") && !e.contains(" 400 ")) {
            return false;
        }
        return e.contains("tool") || e.contains("function") || e.contains("tool_call")
                || e.contains("tools") || e.contains("schema") || e.contains("validation")
                || e.contains("invalid");
    }

    /**
     * Extrait un aperçu utile du body 400 (failed_generation / message).
     * Visible tests.
     */
    public static String summarizeToolValidationError(String error) {
        if (error == null || error.isEmpty()) return "";
        String raw = error.trim();
        try {
            int brace = raw.indexOf('{');
            if (brace >= 0) {
                JSONObject root = new JSONObject(raw.substring(brace));
                JSONObject err = root.optJSONObject("error");
                if (err == null && root.has("message")) err = root;
                if (err != null) {
                    String msg = err.optString("message", "").trim();
                    String failed = err.optString("failed_generation", "").trim();
                    if (!failed.isEmpty()) {
                        String clip = failed.length() > 120
                                ? failed.substring(0, 117).trim() + "…" : failed;
                        return (msg.isEmpty() ? "tool validation" : msg) + " | gen=" + clip;
                    }
                    if (!msg.isEmpty()) return truncateDetail(msg, 200);
                }
            }
        } catch (Exception ignored) {}
        return truncateDetail(raw, 200);
    }

    private static String truncateDetail(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() <= max) return t;
        return t.substring(0, max - 1).trim() + "…";
    }

    private void dispatchToolFromLlm(ConversationManager conv, String llmText, SessionObserver obs) {
        String toolHint = ToolDispatcher.guessToolId(llmText);
        if (turnReasoning != null && toolHint != null && !toolHint.isEmpty()) {
            turnReasoning.noteToolStart(toolHint, new JSONObject());
        }
        final long startedAt = System.currentTimeMillis();
        toolDispatcher.dispatch(appContext, llmText, new ToolCallback() {
            @Override
            public void onSuccess(ToolResult result) {
                if (turnReasoning != null && toolHint != null && !toolHint.isEmpty()) {
                    String preview = result != null ? result.text : "";
                    turnReasoning.noteToolEnd(toolHint, true,
                            System.currentTimeMillis() - startedAt, preview);
                }
                deliverToolResult(conv, toolHint != null ? toolHint : "outil",
                        result, false, obs);
            }

            @Override
            public void onSuccessAndExit(ToolResult result) {
                if (turnReasoning != null && toolHint != null && !toolHint.isEmpty()) {
                    String preview = result != null ? result.text : "";
                    turnReasoning.noteToolEnd(toolHint, true,
                            System.currentTimeMillis() - startedAt, preview);
                }
                deliverToolResult(conv, toolHint != null ? toolHint : "outil",
                        result, true, obs);
            }

            @Override
            public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                main.post(() -> handleToolConfirm(obs, question, onConfirm, onCancel));
            }

            @Override
            public void onChoiceNeeded(String title, String[] labels,
                    java.util.function.IntConsumer onChosen, Runnable onCancel) {
                main.post(() -> handleToolChoice(obs, title, labels, onChosen, onCancel));
            }

            @Override
            public void onProgress(String message) {
                if (message != null && !message.isEmpty()) {
                    notifyToolProgress(obs, message);
                }
            }

            @Override
            public void onError(String error) {
                if (turnReasoning != null && toolHint != null && !toolHint.isEmpty()) {
                    turnReasoning.noteToolEnd(toolHint, false,
                            System.currentTimeMillis() - startedAt, error);
                }
                notifyError(obs, error);
            }
        });
    }

    private void finishTool(ConversationManager conv, String toolId, long startedAt,
            ToolResult result, String error, boolean exit, SessionObserver obs) {
        long duration = System.currentTimeMillis() - startedAt;
        if (error != null) {
            if (turnReasoning != null) {
                turnReasoning.noteToolEnd(toolId, false, duration, error);
            }
            clearAgenticState();
            Trace.toolEnd(toolId, false, duration, null, error);
            Trace.toolFailureContext(toolId, "tool_execute_error", error,
                    conv != null ? conv.getLastUserText() : null);
            notifyToolEnd(obs, toolId, false);
            notifyError(obs, error);
            return;
        }
        if (turnReasoning != null) {
            String preview = result != null ? result.wireText() : "";
            if (preview == null || preview.isEmpty()) {
                preview = result != null ? result.text : "";
            }
            turnReasoning.noteToolEnd(toolId, true, duration, preview);
        }
        Trace.toolEnd(toolId, true, duration,
                result != null ? result.wireText() : "", null);
        notifyToolEnd(obs, toolId, true);
        deliverToolResult(conv, toolId, result != null ? result : ToolResult.text(""),
                exit, obs);
    }

    private void deliverToolResult(ConversationManager conv, String toolId, ToolResult result,
            boolean exit, SessionObserver obs) {
        LlmReply assistantReply = pendingAssistantToolReply;
        NativeToolCall nativeCall = pendingNativeToolCall;
        clearPendingToolCall();

        // Forcer le contexte positif avant tout appel LLM de synthèse
        String successText = displayForUser(result);
        if (conv != null) {
            conv.recordToolSuccessHint(
                    toolId != null ? toolId : (nativeCall != null ? nativeCall.name : "outil"),
                    successText);
        }

        if (useAgenticLoop() && !exit && assistantReply != null && nativeCall != null) {
            if (activeAgenticChain == null) {
                activeAgenticChain = new AgenticChain(
                        conv.historySnapshot(), conv.getLastUserText());
                activeAgenticOptions = buildSendOptions(conv.getLastUserText());
                agenticStepIndex = 0;
            } else if (conv != null) {
                // Garder la chaîne alignée avec le hint system fraîchement ajouté
                activeAgenticChain.history.clear();
                activeAgenticChain.history.addAll(conv.historySnapshot());
            }
            activeAgenticChain.addStep(assistantReply, nativeCall,
                    synthesisInput(result), displayForUser(result));
            if (result.kind == ToolResult.Kind.IMAGE_URL) {
                notifyToolResult(obs, result);
            }
            runAgenticStep(conv, obs);
            return;
        }

        // Outil direct (voix / JSON) — pas de synthèse agentique
        if (result.kind == ToolResult.Kind.IMAGE_URL) {
            // Évite d'injecter le brief anglais APOD dans l'historique / TTS.
            String placeholder = "Voici la photo NASA du jour.";
            conv.recordToolReply(placeholder);
            publishReasoningForReply(placeholder);
            if (exit) {
                notifyToolExit(obs, result);
            } else {
                notifyToolResult(obs, result);
            }
            return;
        }

        conv.recordToolReply(result.text);
        publishReasoningForReply(result.text);
        if (exit) {
            notifyToolExit(obs, result);
        } else {
            notifyToolResult(obs, result);
        }
    }

    private void runAgenticStep(ConversationManager conv, SessionObserver obs) {
        runAgenticStep(conv, obs, computeAllowMoreTools());
    }

    private void runAgenticStep(ConversationManager conv, SessionObserver obs,
            boolean allowMoreTools) {
        AgenticTurnPolicy.Evaluation eval = AgenticTurnPolicy.evaluate(
                activeAgenticChain, null);
        Trace.agenticStep(activeRequestId, agenticStepIndex, eval.toolStepCount,
                allowMoreTools, eval.sameToolSameArgsCount,
                AgenticTurnPolicy.MAX_TOOLS_PER_TURN);
        ChatSendOptions stepOpts = ChatSendOptions.agenticStep(
                activeAgenticOptions != null
                        ? activeAgenticOptions.allowedTools
                        : ChatSendOptions.legacy().allowedTools,
                allowMoreTools,
                activeAgenticOptions != null
                        ? activeAgenticOptions.channel
                        : sessionContext.channel);
        if (activeAgenticOptions != null && activeAgenticOptions.intentName != null) {
            stepOpts = stepOpts.withIntentName(activeAgenticOptions.intentName);
        }
        Channel channel = activeAgenticOptions != null
                ? activeAgenticOptions.channel
                : sessionContext.channel;
        if (channel == Channel.VOICE) {
            stepOpts = stepOpts.withVoiceTokenBudget(appContext);
        }
        // Synthèse après search/wiki : budget adapté au canal
        if (!allowMoreTools && activeAgenticChain != null
                && !activeAgenticChain.steps().isEmpty()) {
            AgenticChain.Step last = activeAgenticChain.steps()
                    .get(activeAgenticChain.steps().size() - 1);
            String lastTool = last.toolCall != null ? last.toolCall.name : "";
            if (ToolSuccessHint.isInformational(lastTool)) {
                int cap = channel == Channel.VOICE
                        ? Math.min(Math.max(stepOpts.replyMaxTokens(), 220), 280)
                        : 700;
                if (stepOpts.replyMaxTokens() < cap) {
                    stepOpts = stepOpts.withMaxTokens(cap);
                }
            }
        }
        agenticStepIndex++;
        notifyLlmStart(obs);
        final long agenticGeneration = agenticOperationGeneration;
        boolean streamVoiceSynthesis = !allowMoreTools
                && channel == Channel.VOICE
                && sessionContext.streamingEnabled
                && conv.supportsStreaming();
        ChatBackend.OnReply agenticCallback;
        if (streamVoiceSynthesis) {
            agenticCallback = new ChatBackend.StreamOnReply() {
                @Override
                public void onPartial(String accumulated) {
                    main.post(() -> {
                        if (!isAgenticGenerationCurrent(agenticGeneration)) return;
                        notifyPartial(obs, accumulated);
                    });
                }

                @Override
                public void onLlmReply(LlmReply reply) {
                    main.post(() -> {
                        if (!isAgenticGenerationCurrent(agenticGeneration)) return;
                        handleAgenticStepReply(conv, reply, obs);
                    });
                }

                @Override
                public void onReply(String text) {
                    main.post(() -> {
                        if (!isAgenticGenerationCurrent(agenticGeneration)) return;
                        finalizeAgentic(conv, obs, text);
                    });
                }

                @Override
                public void onError(String error) {
                    main.post(() -> {
                        if (!isAgenticGenerationCurrent(agenticGeneration)) return;
                        if (isToolChoiceConflict(error)) {
                            Trace.error("agentic", "tool_choice_conflict: " + error);
                            finalizeAgentic(conv, obs, activeAgenticChain != null
                                    ? activeAgenticChain.lastToolDisplayText() : error);
                            return;
                        }
                        String fallback = activeAgenticChain != null
                                ? activeAgenticChain.lastToolDisplayText() : error;
                        finalizeAgentic(conv, obs, fallback.isEmpty() ? error : fallback);
                    });
                }
            };
        } else {
            agenticCallback = new ChatBackend.OnReply() {
                @Override
                public void onLlmReply(LlmReply reply) {
                    main.post(() -> {
                        if (!isAgenticGenerationCurrent(agenticGeneration)) return;
                        handleAgenticStepReply(conv, reply, obs);
                    });
                }

                @Override
                public void onReply(String text) {
                    main.post(() -> {
                        if (!isAgenticGenerationCurrent(agenticGeneration)) return;
                        finalizeAgentic(conv, obs, text);
                    });
                }

                @Override
                public void onError(String error) {
                    main.post(() -> {
                        if (!isAgenticGenerationCurrent(agenticGeneration)) return;
                        if (isToolChoiceConflict(error)) {
                            Trace.error("agentic", "tool_choice_conflict: " + error);
                            finalizeAgentic(conv, obs, activeAgenticChain != null
                                    ? activeAgenticChain.lastToolDisplayText() : error);
                            return;
                        }
                        String fallback = activeAgenticChain != null
                                ? activeAgenticChain.lastToolDisplayText() : error;
                        finalizeAgentic(conv, obs, fallback.isEmpty() ? error : fallback);
                    });
                }
            };
        }
        conv.sendAgenticStep(activeAgenticChain, stepOpts, agenticCallback);
    }

    private boolean computeAllowMoreTools() {
        if (activeAgenticChain == null) return false;
        return AgenticTurnPolicy.allowMoreToolCalls(activeAgenticChain);
    }

    private static boolean isToolChoiceConflict(String error) {
        return ChatSpokenErrors.isToolChoiceConflict(error);
    }

    /** Visible tests — même heuristique que le recovery agentique. */
    public static boolean isToolChoiceConflictError(String error) {
        return ChatSpokenErrors.isToolChoiceConflict(error);
    }

    private void handleAgenticStepReply(ConversationManager conv, LlmReply reply,
            SessionObserver obs) {
        if (reply.hasNativeToolCalls()) {
            NativeToolCall call = reply.toolCalls.get(0);
            AgenticTurnPolicy.BlockReason block = AgenticTurnPolicy.blockReason(
                    activeAgenticChain, call);
            if (block != AgenticTurnPolicy.BlockReason.NONE) {
                AgenticTurnPolicy.Evaluation eval = AgenticTurnPolicy.evaluate(
                        activeAgenticChain, call);
                Trace.agenticBlocked(activeRequestId, call.name, block.name(),
                        eval.toolStepCount, eval.sameToolSameArgsCount);
                // brief déjà joué (ex. brief({}) vide après « plus de détail ») → prose cache, pas de 2e outil
                if (activeAgenticChain != null && activeAgenticChain.usedTool("brief")
                        && "brief".equalsIgnoreCase(call.name)) {
                    String detail = BriefTool.composeBriefDetail(appContext);
                    if (detail == null || detail.trim().isEmpty()) {
                        detail = activeAgenticChain.lastToolDisplayText();
                    }
                    finalizeAgentic(conv, obs, detail);
                    return;
                }
                // orion_manager({}) répété → finaliser avec le statut déjà obtenu, pas de nouvel appel LLM
                if (activeAgenticChain != null && activeAgenticChain.usedTool("orion_manager")
                        && "orion_manager".equalsIgnoreCase(call.name)) {
                    finalizeAgentic(conv, obs, activeAgenticChain.lastToolDisplayText());
                    return;
                }
                runAgenticStep(conv, obs, false);
                return;
            }
            String preview = reply.content != null ? reply.content : "";
            notifyReply(obs, preview, true);
            if (!allowToolExecution(obs)) {
                conv.recordToolReply(LockSessionPolicy.UNLOCK_TOOL_MESSAGE);
                notifyToolBlocked(obs);
                clearAgenticState();
                return;
            }
            pendingAssistantToolReply = reply;
            pendingNativeToolCall = call;
            executeToolInternal(call.name, call.arguments, null, obs, false);
            return;
        }
        finalizeAgentic(conv, obs, reply.content != null ? reply.content : "");
    }

    private void finalizeAgentic(ConversationManager conv, SessionObserver obs, String text) {
        String out = text != null ? text.trim() : "";
        if (out.isEmpty() && activeAgenticChain != null) {
            out = activeAgenticChain.lastToolDisplayText();
        }
        clearAgenticState();
        if (out.isEmpty()) {
            notifyError(obs, "Je n'ai pas pu formuler la réponse.");
            return;
        }
        conv.recordToolReply(out);
        markTurnLlmSynthesis(conv);
        publishReasoningForReply(out);
        notifyReply(obs, out, false);
    }

    private void markTurnLlmSynthesis(ConversationManager conv) {
        if (turnReasoning == null || conv == null) return;
        turnReasoning.markLlmSynthesis(
                conv.lastLlmBackend(), conv.lastLlmLatencyMs(), conv.lastPromptChars());
    }

    private void beginTurnReasoning(String userMessage) {
        String msg = userMessage != null ? userMessage : "";
        currentTurnIntent = ContextAnalyzer.analyze(appContext, msg, true);
        turnReasoning = new ReasoningTurnCollector(currentTurnIntent.intent);
        if (appContext == null) return;
        try {
            // Même sélection que le prompt — pas un 2e scoring divergé
            ContextSnapshot snap = ContextBuilder.buildSnapshot(
                    appContext, msg, currentTurnIntent, sessionContext.channel);
            turnReasoning.applySnapshot(snap);
            turnReasoning.setSessionUsed(msg);
        } catch (Exception ignored) {
        }
    }

    private void publishReasoningForReply(String reply) {
        if (reply == null || reply.trim().isEmpty()) return;
        if (turnReasoning == null) {
            turnReasoning = new ReasoningTurnCollector("general");
        }
        ConversationManager conv = conversation();
        String backend = conv != null ? conv.lastLlmBackend() : "";
        long latency = conv != null ? conv.lastLlmLatencyMs() : 0L;
        int promptChars = conv != null ? conv.lastPromptChars() : 0;
        ReasoningCard card = turnReasoning.build(reply.trim(), backend, latency, promptChars);
        // Indexer sur le texte brut ET la version affichée/stockée (tronquée)
        ReasoningStore.put(reply.trim(), card);
        String stored = com.pegasuscorp.orbe.memory.ConversationHistorySanitizer
                .forAssistant(reply.trim());
        if (stored != null && !stored.isEmpty()) {
            ReasoningStore.put(stored, card);
        }
        String cleaned = com.pegasuscorp.orbe.tools.ToolDispatcher.cleanForDisplay(reply.trim());
        if (cleaned != null && !cleaned.isEmpty()) {
            ReasoningStore.put(cleaned, card);
        }
        Trace.reasoningCard(card);
        turnReasoning = null;
        currentTurnIntent = null;
    }

    private boolean isAgenticGenerationCurrent(long captured) {
        return captured == agenticOperationGeneration;
    }

    private void clearPendingToolCall() {
        pendingAssistantToolReply = null;
        pendingNativeToolCall = null;
    }

    private void clearAgenticState() {
        agenticOperationGeneration++;
        clearPendingToolCall();
        activeAgenticChain = null;
        activeAgenticOptions = null;
        agenticStepIndex = 0;
    }

    private static String synthesisInput(ToolResult result) {
        if (result == null) return "";
        if (result.kind == ToolResult.Kind.IMAGE_URL) {
            return result.text.isEmpty() ? "Photo NASA du jour affichée." : result.text;
        }
        return result.contextForSynthesis();
    }

    private static String displayForUser(ToolResult result) {
        if (result == null) return "";
        return result.text;
    }

    private boolean useAgenticLoop() {
        return useNativeFunctionCalling();
    }

    /** True pendant une boucle agentique (synthèse LLM après outil). */
    public boolean hasActiveAgenticChain() {
        return activeAgenticChain != null;
    }

    private ConversationManager conversation() {
        if (conversationOverride != null) {
            return conversationOverride;
        }
        return ChatSessionRegistry.get(appContext);
    }

    private void ensureEntered(ConversationManager conv) {
        if (!conv.isActive()) {
            conv.enter();
        }
    }

    private void traceUserIngress(String text) {
        if (sessionContext.channel == Channel.VOICE) {
            return;
        }
        Trace.userMessage(text, channelTraceSource(), false);
    }

    private String channelTraceSource() {
        if (sessionContext.channel == Channel.VOICE) return "voice";
        if (sessionContext.channel == Channel.BUREAU) return "bureau";
        return "text";
    }

    private boolean allowToolExecution(SessionObserver oneOff) {
        if (oneOff != null && !oneOff.allowToolExecution()) return false;
        for (SessionObserver o : observers) {
            if (!o.allowToolExecution()) return false;
        }
        return true;
    }

    private void notifyReply(SessionObserver oneOff, String text, boolean toolFired) {
        notify(oneOff, o -> o.onReply(text, toolFired));
    }

    private void notifyPartial(SessionObserver oneOff, String accumulated) {
        notify(oneOff, o -> o.onPartial(accumulated));
    }

    private void notifyToolProgress(SessionObserver oneOff, String message) {
        notify(oneOff, o -> {
            o.onToolProgress(message);
            o.onPartial(message);
        });
    }

    private void notifyToolStart(SessionObserver oneOff, String toolId) {
        notify(oneOff, o -> o.onToolStart(toolId));
    }

    private void notifyToolEnd(SessionObserver oneOff, String toolId, boolean ok) {
        notify(oneOff, o -> o.onToolEnd(toolId, ok));
    }

    private void notifyLlmStart(SessionObserver oneOff) {
        notify(oneOff, SessionObserver::onLlmStart);
    }

    private void notifyToolResult(SessionObserver oneOff, ToolResult result) {
        notify(oneOff, o -> o.onToolResult(result));
    }

    private void notifyToolExit(SessionObserver oneOff, ToolResult result) {
        notify(oneOff, o -> o.onToolExit(result));
    }

    private void notifyToolBlocked(SessionObserver oneOff) {
        notify(oneOff, SessionObserver::onToolBlocked);
    }

    private void notifyError(SessionObserver oneOff, String message) {
        notify(oneOff, o -> o.onError(ChatSpokenErrors.toUserMessage(message)));
    }

    private void notify(SessionObserver oneOff, ObserverAction action) {
        if (oneOff != null) {
            action.apply(oneOff);
        }
        for (SessionObserver o : observers) {
            action.apply(o);
        }
    }

    private interface ObserverAction {
        void apply(SessionObserver observer);
    }

    /**
     * Sur backend fallback (120b / Qwen), bloquer notepad(add) si le texte ressemble
     * à un diagnostic inventé.
     */
    static boolean shouldBlockNotepadDiagFallback(Context ctx, String toolId, JSONObject params) {
        if (ctx == null || toolId == null || !"notepad".equalsIgnoreCase(toolId.trim())) {
            return false;
        }
        if (!BriefTool.isOnFallbackBackend(ctx)) return false;
        if (params == null) return false;
        String action = params.optString("action", "list").trim().toLowerCase(Locale.ROOT);
        if (!"add".equals(action)) return false;
        return containsDiagFallbackKeywords(params.optString("text", ""));
    }

    /** Mots typiques d'un diag inventé par le modèle de repli. */
    static boolean containsDiagFallbackKeywords(String text) {
        if (text == null || text.isEmpty()) return false;
        String f = text.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a').replace('â', 'a')
                .replace('ô', 'o').replace('ù', 'u').replace('û', 'u')
                .replace('ç', 'c')
                .replace('’', '\'');
        return f.contains("bug")
                || f.contains("erreur")
                || f.contains("plantage")
                || f.contains("probleme")
                || f.contains("notification")
                || f.contains("synchronisation");
    }
}
