package com.pegasuscorp.orbe.chat;

import android.content.Context;

import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.memory.ConversationHistorySanitizer;
import com.pegasuscorp.orbe.memory.MemoryStore;
import com.pegasuscorp.orbe.memory.MemoryRepository;
import com.pegasuscorp.orbe.memory.SessionSummarizer;
import com.pegasuscorp.orbe.tools.ToolDispatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Gère le mode discussion : état actif, historique court et persistance mémoire.
 *
 * INVARIANT CENTRAL : l'historique ne doit JAMAIS se terminer par un tour utilisateur
 * une fois l'échange terminé. Sinon le LLM voit la demande précédente comme encore
 * en attente et la ré-exécute au tour suivant (boucle de répétition).
 */
public class ConversationManager {

    private static final String[] ACTION_CLAIMS = {
            "c'est noté", "c'est bien noté", "c'est fait", "j'ai ajouté",
            "j'ai noté", "je l'ai noté", "je l'ai ajouté", "j'ai mis", "je l'ai mis",
            "j'ai enregistré", "ajouté au bloc", "c'est enregistré", "c'est ajouté",
            "voilà, c'est"
    };

    /** Affirmations typiques « notepad » — fantôme même si la demande user est ambiguë. */
    private static final String[] NOTEPAD_CLAIMS = {
            "j'ai noté", "je l'ai noté", "c'est noté", "c'est bien noté",
            "ajouté au bloc", "c'est ajouté", "j'ai ajouté", "je l'ai ajouté"
    };

    private static final String[] ACTION_INTENTS = {
            "ajoute", "note ", "supprime", "efface", "allume", "éteins",
            "mets ", "règle", "programme", "crée"
    };

    private final ChatBackend backend;
    private final MemoryStore memory;
    private final Context appContext;
    private final List<ChatBackend.Turn> history = new ArrayList<>();
    private final List<ChatBackend.Turn> sessionTurns = new ArrayList<>();
    private boolean active = false;

    /** Un tour utilisateur a été ajouté et attend encore sa réponse assistant. */
    private boolean userTurnPending = false;

    private long lastSendAtMs;
    private boolean lastSendStreamed;
    private int lastPromptChars;
    private String lastUserText;
    private String lastLlmBackend = "";
    private long lastLlmLatencyMs;
    /** Outil réussi dans ce tour (ex. memory) — la prose de synthèse n'est pas un fantôme. */
    private boolean toolSucceededThisTurn;

    /**
     * Incrémenté à chaque nouveau tour utilisateur ({@link #send}, {@link #addUserMessage}…).
     * Les callbacks LLM en vol d'un tour précédent sont ignorés.
     */
    private long sendGeneration;

    public ConversationManager(Context context, ChatBackend backend) {
        this(backend, MemoryRepository.getInstance(context.getApplicationContext()),
                context.getApplicationContext());
    }

    /** Injection explicite — tests unitaires et future PegaseSession. */
    ConversationManager(ChatBackend backend, MemoryStore memory, Context appContext) {
        this.backend = backend;
        this.memory = memory;
        this.appContext = appContext;
    }

    /** Tests sans Context Android (pas de résumé de session à la sortie). */
    public ConversationManager(ChatBackend backend, MemoryStore memory) {
        this(backend, memory, null);
    }

    public boolean isActive() { return active; }

    public boolean supportsStreaming() {
        return backend.supportsStreaming();
    }

    public void enter() {
        active = true;
        userTurnPending = false;
        lastUserText = null;
        sessionTurns.clear();
        history.clear();
        // Garder une demande utilisateur encore sans réponse (sinon elle disparaît de l'UI).
        List<ChatBackend.Turn> cleaned =
                ConversationHistorySanitizer.normalizeKeepingTrailingUser(memory.getRecentTurns());
        history.addAll(cleaned);
        memory.setRecentTurns(cleaned);
        if (!history.isEmpty() && history.get(history.size() - 1).fromUser) {
            userTurnPending = true;
            lastUserText = history.get(history.size() - 1).text;
        }
    }

    /** @return true si une session avec échanges a été enregistrée */
    public boolean exit() {
        boolean hadSession = active && !sessionTurns.isEmpty();
        if (hadSession) {
            // Archivage : ne pas laisser de tour user orphelin pour le prochain LLM.
            memory.setRecentTurns(ConversationHistorySanitizer.normalize(history));
            if (appContext != null) {
                SessionSummarizer.summarizeAndSave(appContext, new ArrayList<>(sessionTurns));
            }
        }
        active = false;
        userTurnPending = false;
        sessionTurns.clear();
        return hadSession;
    }

    public void send(String userMessage, ChatBackend.OnReply callback) {
        send(userMessage, userMessage, callback);
    }

    public void send(String payload, String displayText, ChatBackend.OnReply callback) {
        send(payload, displayText, callback, ChatSendOptions.legacy());
    }

    /**
     * @param payload     texte réellement envoyé au LLM (peut contenir des hints internes)
     * @param displayText texte enregistré dans l'historique / affiché à l'écran
     */
    public void send(String payload, String displayText, ChatBackend.OnReply callback,
            ChatSendOptions options) {
        String userText = displayText != null ? displayText : payload;
        if (userTurnPending && !history.isEmpty() && history.get(history.size() - 1).fromUser) {
            replaceLastUserTurn(userText);
        } else if (!userTurnPending) {
            addTurn(true, userText);
        } else {
            addTurn(true, userText);
        }
        userTurnPending = true;
        lastUserText = userText;
        final long callbackGeneration = bumpSendGeneration();

        ChatBackend.OnReply wrapped;
        if (callback instanceof ChatBackend.StreamOnReply) {
            ChatBackend.StreamOnReply streamCb = (ChatBackend.StreamOnReply) callback;
            wrapped = new ChatBackend.StreamOnReply() {
                @Override
                public void onPartial(String accumulated) {
                    streamCb.onPartial(accumulated);
                }

                @Override
                public void onReply(String text) {
                    if (isStaleCallback(callbackGeneration)) return;
                    streamCb.onReply(recordAssistantReply(text));
                }

                @Override
                public void onLlmReply(LlmReply reply) {
                    if (isStaleCallback(callbackGeneration)) return;
                    if (reply.hasNativeToolCalls()) {
                        recordNativeToolAssistant(reply);
                        streamCb.onLlmReply(reply);
                    } else {
                        streamCb.onReply(recordAssistantReply(
                                reply.content != null ? reply.content : ""));
                    }
                }

                @Override
                public void onError(String error) {
                    if (isStaleCallback(callbackGeneration)) return;
                    if (ChatSpokenErrors.isToolChoiceConflict(error)) {
                        streamCb.onError(error);
                        return;
                    }
                    String userMsg = ChatSpokenErrors.toUserMessage(error);
                    recordAssistantError(error, userMsg);
                    streamCb.onError(userMsg);
                }
            };
        } else {
            wrapped = new ChatBackend.OnReply() {
                @Override
                public void onReply(String text) {
                    if (isStaleCallback(callbackGeneration)) return;
                    callback.onReply(recordAssistantReply(text));
                }

                @Override
                public void onLlmReply(LlmReply reply) {
                    if (isStaleCallback(callbackGeneration)) return;
                    if (reply.hasNativeToolCalls()) {
                        recordNativeToolAssistant(reply);
                        callback.onLlmReply(reply);
                    } else {
                        callback.onReply(recordAssistantReply(
                                reply.content != null ? reply.content : ""));
                    }
                }

                @Override
                public void onError(String error) {
                    if (isStaleCallback(callbackGeneration)) return;
                    if (ChatSpokenErrors.isToolChoiceConflict(error)) {
                        callback.onError(error);
                        return;
                    }
                    String userMsg = ChatSpokenErrors.toUserMessage(error);
                    recordAssistantError(error, userMsg);
                    callback.onError(userMsg);
                }
            };
        }
        Trace.historySnapshot(history, "before_send");
        lastSendAtMs = System.currentTimeMillis();
        lastSendStreamed = callback instanceof ChatBackend.StreamOnReply;
        lastPromptChars = estimatePromptChars(history, payload);
        ChatSendOptions opts = options != null ? options : ChatSendOptions.legacy();
        backend.send(new ArrayList<>(history), payload, wrapped, opts);
    }

    private void recordNativeToolAssistant(LlmReply reply) {
        int count = reply.toolCalls != null ? reply.toolCalls.size() : 0;
        noteLlmMeta(backend.traceBackendLabel(), System.currentTimeMillis() - lastSendAtMs);
        Trace.llmReply("[native tool_calls:" + count + "]", lastLlmBackend,
                lastLlmLatencyMs, lastSendStreamed,
                true, false, lastPromptChars);
        if (reply.content != null && !reply.content.trim().isEmpty()) {
            addTurn(false, reply.content.trim());
        }
    }

    private void noteLlmMeta(String backendLabel, long latencyMs) {
        lastLlmBackend = backendLabel != null ? backendLabel : "";
        lastLlmLatencyMs = Math.max(0L, latencyMs);
    }

    /** Backend du dernier llm_reply (pour ReasoningCard). */
    public String lastLlmBackend() {
        return lastLlmBackend != null ? lastLlmBackend : "";
    }

    public long lastLlmLatencyMs() {
        return lastLlmLatencyMs;
    }

    public int lastPromptChars() {
        return lastPromptChars;
    }

    public void addUserMessage(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) return;
        lastUserText = userMessage.trim();
        toolSucceededThisTurn = false;
        bumpSendGeneration();
        addTurn(true, userMessage);
        userTurnPending = true;
    }

    /** @return texte à afficher / parler (peut différer du brut si filtre anti-fantôme). */
    private String recordAssistantReply(String text) {
        boolean toolCall = ToolDispatcher.isToolCall(text);
        noteLlmMeta(backend.traceBackendLabel(), System.currentTimeMillis() - lastSendAtMs);
        Trace.llmReply(text, lastLlmBackend,
                lastLlmLatencyMs, lastSendStreamed,
                toolCall, ToolDispatcher.looksLikeToolAttempt(text), lastPromptChars);

        // Après un outil réussi (memory, notepad…), la synthèse « C'est noté » est légitime.
        if (!toolCall && !toolSucceededThisTurn) {
            text = guardPhantom(text, false);
        }

        if (toolCall) {
            String preamble = ToolDispatcher.stripToolCall(text);
            if (!preamble.isEmpty()) {
                addTurn(false, preamble);
                userTurnPending = false;
            }
            return text;
        }
        addTurn(false, text);
        userTurnPending = false;
        return text;
    }

    private void recordAssistantError(String rawError, String userMessage) {
        Trace.error("llm", rawError);
        if (!userTurnPending) return;
        // Ne jamais stocker « quota Groq » / rate limit : ça pollue les tours suivants.
        String text;
        if (ChatSpokenErrors.isRateLimit(rawError)
                || ChatSpokenErrors.isHistoryPoison(rawError)
                || ChatSpokenErrors.isHistoryPoison(userMessage)) {
            text = ChatSpokenErrors.HISTORY_SAFE_TRANSIENT_ERROR;
        } else {
            text = ChatSpokenErrors.toHistoryMessage(
                    userMessage != null && !userMessage.trim().isEmpty() ? userMessage : rawError);
        }
        if (text == null || text.trim().isEmpty()) {
            text = ChatSpokenErrors.HISTORY_SAFE_TRANSIENT_ERROR;
        }
        addTurn(false, text);
        userTurnPending = false;
    }

    private boolean claimsAction(String reply) {
        String r = foldForPhantom(reply);
        for (String k : ACTION_CLAIMS) {
            if (r.contains(foldForPhantom(k))) return true;
        }
        return false;
    }

    /** « j'ai noté » / « c'est noté » etc. — fantôme notepad sans outil. */
    private boolean claimsNotepadAction(String reply) {
        String r = foldForPhantom(reply);
        for (String k : NOTEPAD_CLAIMS) {
            if (r.contains(foldForPhantom(k))) return true;
        }
        return false;
    }

    private boolean looksLikeActionRequest(String user) {
        if (user == null) return false;
        String u = foldForPhantom(user);
        for (String k : ACTION_INTENTS) {
            if (u.contains(foldForPhantom(k))) return true;
        }
        return false;
    }

    /**
     * Filtre anti-fantôme — s'applique à toute réponse prose (120B, Scout, Gemini…),
     * car tout passe par {@link #recordAssistantReply}.
     * Notepad : « j'ai noté » sans outil = fantôme même si la demande est ambiguë.
     */
    private String guardPhantom(String reply, boolean toolFired) {
        if (toolFired || reply == null || reply.isEmpty()) return reply;
        boolean notepadPhantom = claimsNotepadAction(reply);
        boolean generalPhantom = looksLikeActionRequest(lastUserText) && claimsAction(reply);
        if (!notepadPhantom && !generalPhantom) return reply;

        Trace.phantomBlocked(lastUserText, reply);
        String tool = notepadPhantom ? "notepad" : guessIntendedTool(lastUserText);
        Trace.toolHesitation(tool,
                "phantom_action",
                "prose affirmant une action sans outil exécuté",
                lastUserText);
        return "Je n'ai pas réussi à faire ça — aucune action n'a été exécutée. "
                + "Tu veux que je réessaie ?";
    }

    /** Minuscule + apostrophes normalisées (Scout / 120B). */
    static String foldForPhantom(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'')
                .replace('\u2018', '\'')
                .replace('`', '\'');
    }

    /** Heuristique légère pour enrichir tool_hesitation (notepad, etc.). */
    static String guessIntendedTool(String user) {
        if (user == null) return "unknown";
        String u = user.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e');
        if (u.contains("souviens") || u.contains("retenir") || u.contains("retiens")
                || u.contains("memorise") || u.contains("memoire")
                || u.contains("oublie que") || u.contains("n'oublie pas")) {
            return "memory";
        }
        if (u.contains("note") || u.contains("liste") || u.contains("ajoute ")
                || u.contains("rappelle") || u.contains("bloc-notes") || u.contains("bloc notes")) {
            return "notepad";
        }
        if (u.contains("ouvre") || u.contains("lance ")) return "open_app";
        if (u.contains("meteo") || u.contains("météo") || u.contains("temps")) return "weather";
        if (u.contains("spotify") || u.contains("musique")) return "spotify";
        if (u.contains("alarme") || u.contains("réveil") || u.contains("reveil")) return "alarm";
        return "unknown";
    }

    private static int estimatePromptChars(List<ChatBackend.Turn> turns, String payload) {
        int n = payload != null ? payload.length() : 0;
        if (turns != null) {
            for (ChatBackend.Turn t : turns) {
                if (t.text != null) n += t.text.length();
            }
        }
        return n;
    }

    private void addTurn(boolean fromUser, String text) {
        String stored = ConversationHistorySanitizer.forStorage(fromUser, text);
        if (stored.isEmpty()) return;
        ChatBackend.Turn turn = new ChatBackend.Turn(fromUser, stored);
        if (fromUser && !history.isEmpty() && history.get(history.size() - 1).fromUser) {
            history.set(history.size() - 1, turn);
            if (!sessionTurns.isEmpty() && sessionTurns.get(sessionTurns.size() - 1).fromUser) {
                sessionTurns.set(sessionTurns.size() - 1, turn);
            } else {
                sessionTurns.add(turn);
            }
            memory.replaceLastUserTurn(stored);
            return;
        }
        history.add(turn);
        sessionTurns.add(turn);
        trimHistory(history);
        memory.addTurn(fromUser, stored);
    }

    private void replaceLastUserTurn(String text) {
        String stored = ConversationHistorySanitizer.forUser(text);
        if (stored.isEmpty()) return;
        ChatBackend.Turn turn = new ChatBackend.Turn(true, stored);
        if (!history.isEmpty() && history.get(history.size() - 1).fromUser) {
            history.set(history.size() - 1, turn);
        }
        if (!sessionTurns.isEmpty() && sessionTurns.get(sessionTurns.size() - 1).fromUser) {
            sessionTurns.set(sessionTurns.size() - 1, turn);
        }
        memory.replaceLastUserTurn(stored);
    }

    public void recordToolReply(String spokenReply) {
        String cleaned = ConversationHistorySanitizer.forAssistant(spokenReply);
        if (cleaned.isEmpty()) {
            userTurnPending = false;
            return;
        }

        toolSucceededThisTurn = true;
        replaceOrAppendAssistant(history, cleaned);
        replaceOrAppendAssistant(sessionTurns, cleaned);
        trimHistory(history);
        userTurnPending = false;

        memory.replaceLastAssistantTurn(cleaned);
    }

    /**
     * Après tool_end ok=true : contexte pour le prochain appel LLM.
     * Non affiché comme bulle (turn.system) ; non persisté en mémoire longue.
     */
    public void recordToolSuccessHint(String toolName, String resultText) {
        toolSucceededThisTurn = true;
        history.add(ChatBackend.Turn.system(ToolSuccessHint.build(toolName, resultText)));
        trimHistory(history);
    }

    /** Tour utilisateur sans appel LLM (réponse à une confirm/choix outil). */
    public void recordUserMessage(String text) {
        if (text == null || text.trim().isEmpty()) return;
        bumpSendGeneration();
        addTurn(true, text.trim());
        userTurnPending = true;
    }

    private static void trimHistory(List<ChatBackend.Turn> turns) {
        while (turns.size() > ConversationHistorySanitizer.MAX_STORED_TURNS) {
            turns.remove(0);
        }
    }

    private static void replaceOrAppendAssistant(List<ChatBackend.Turn> turns, String text) {
        int lastUser = -1;
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i).fromUser) { lastUser = i; break; }
        }
        for (int i = turns.size() - 1; i > lastUser; i--) {
            if (!turns.get(i).fromUser) {
                turns.set(i, new ChatBackend.Turn(false, text));
                return;
            }
        }
        turns.add(new ChatBackend.Turn(false, text));
    }

    /** Snapshot interne — tests PegaseSession étape 0+. */
    public List<ChatBackend.Turn> historySnapshot() {
        return new ArrayList<>(history);
    }

    /**
     * Étape de boucle agentique — peut produire du texte ou de nouveaux tool_calls.
     */
    public void sendAgenticStep(AgenticChain chain, ChatSendOptions options,
            ChatBackend.OnReply callback) {
        ChatSendOptions opts = options != null ? options : ChatSendOptions.legacy();
        final long callbackGeneration = sendGeneration;
        ChatBackend.OnReply wrapped = new ChatBackend.OnReply() {
            @Override
            public void onLlmReply(LlmReply reply) {
                if (isStaleCallback(callbackGeneration)) return;
                if (reply.hasNativeToolCalls()) {
                    callback.onLlmReply(reply);
                    return;
                }
                String text = reply.content != null && !reply.content.isEmpty()
                        ? reply.content
                        : chain.lastToolDisplayText();
                callback.onReply(recordAssistantReply(text));
            }

            @Override
            public void onReply(String text) {
                if (isStaleCallback(callbackGeneration)) return;
                callback.onReply(recordAssistantReply(text));
            }

            @Override
            public void onError(String error) {
                if (isStaleCallback(callbackGeneration)) return;
                // Passer l'erreur brute si tool_choice conflict — PegaseSession récupère le dernier outil
                if (ChatSpokenErrors.isToolChoiceConflict(error)) {
                    callback.onError(error);
                    return;
                }
                String userMsg = ChatSpokenErrors.toUserMessage(error);
                recordAssistantError(error, userMsg);
                callback.onError(userMsg);
            }
        };

        Trace.historySnapshot(history, "before_agentic_step");
        lastSendAtMs = System.currentTimeMillis();
        lastSendStreamed = false;
        lastPromptChars = estimatePromptChars(history, lastUserText);
        backend.sendAgenticContinuation(chain, opts, wrapped);
    }

    /** Compat mono-étape. */
    public void sendAgenticSynthesis(LlmReply assistantReply, NativeToolCall toolCall,
            String toolResultContent, ChatBackend.OnReply callback) {
        AgenticChain chain = new AgenticChain(history, lastUserText);
        chain.addStep(assistantReply, toolCall, toolResultContent);
        sendAgenticStep(chain, ChatSendOptions.agenticStep(
                ChatSendOptions.legacy().allowedTools, false), callback);
    }

    public String getLastUserText() {
        return lastUserText;
    }

    public boolean isUserTurnPending() {
        return userTurnPending;
    }

    private long bumpSendGeneration() {
        return ++sendGeneration;
    }

    private boolean isStaleCallback(long capturedGeneration) {
        if (capturedGeneration == sendGeneration) return false;
        Trace.staleCallbackIgnored(capturedGeneration, sendGeneration);
        return true;
    }

    /**
     * Appel LLM isolé — n'ajoute rien à l'historique (bureau Markdown, canvas).
     * @param traceChannel "bureau" ou null (chat classique si réutilisé ailleurs)
     */
    public void completeEphemeral(String prompt, ChatBackend.OnReply callback,
            ChatSendOptions options) {
        completeEphemeral(prompt, callback, options, null);
    }

    public void completeEphemeral(String prompt, ChatBackend.OnReply callback,
            ChatSendOptions options, String traceChannel) {
        if (prompt == null || prompt.trim().isEmpty()) {
            callback.onError("Prompt vide.");
            return;
        }
        ChatSendOptions opts = options != null ? options : ChatSendOptions.legacy();
        lastSendAtMs = System.currentTimeMillis();
        lastSendStreamed = false;
        lastPromptChars = prompt.length();
        String channel = traceChannel != null && !traceChannel.isEmpty() ? traceChannel : null;
        backend.send(Collections.emptyList(), prompt, wrapEphemeralCallback(callback, channel), opts);
    }

    /**
     * Fil Pégase bureau — historique dédié (ne touche pas {@link #history} chat).
     */
    public void completeBureauThread(List<ChatBackend.Turn> bureauHistory, String prompt,
            ChatBackend.OnReply callback, ChatSendOptions options, String traceChannel) {
        if (prompt == null || prompt.trim().isEmpty()) {
            callback.onError("Prompt vide.");
            return;
        }
        ChatSendOptions opts = options != null ? options : ChatSendOptions.legacy();
        lastSendAtMs = System.currentTimeMillis();
        lastSendStreamed = false;
        lastPromptChars = prompt.length();
        String channel = traceChannel != null && !traceChannel.isEmpty() ? traceChannel : "bureau";
        List<ChatBackend.Turn> hist = bureauHistory != null
                ? new ArrayList<>(bureauHistory) : Collections.emptyList();
        backend.send(hist, prompt, wrapEphemeralCallback(callback, channel), opts);
    }

    private ChatBackend.OnReply wrapEphemeralCallback(ChatBackend.OnReply callback,
            String channel) {
        return new ChatBackend.OnReply() {
            @Override
            public void onLlmReply(LlmReply reply) {
                String text = reply.content != null ? reply.content : "";
                noteLlmMeta(backend.traceBackendLabel(),
                        System.currentTimeMillis() - lastSendAtMs);
                Trace.llmReply(text, lastLlmBackend,
                        lastLlmLatencyMs, false,
                        false, false, lastPromptChars, true, channel);
                callback.onLlmReply(reply);
            }

            @Override
            public void onReply(String text) {
                noteLlmMeta(backend.traceBackendLabel(),
                        System.currentTimeMillis() - lastSendAtMs);
                Trace.llmReply(text, lastLlmBackend,
                        lastLlmLatencyMs, false,
                        false, false, lastPromptChars, true, channel);
                callback.onReply(text);
            }

            @Override
            public void onError(String error) {
                String userMsg = ChatSpokenErrors.toUserMessage(error);
                Trace.llmReply("[error] " + error, backend.traceBackendLabel(),
                        System.currentTimeMillis() - lastSendAtMs, false,
                        false, false, lastPromptChars, true, channel);
                callback.onError(userMsg);
            }
        };
    }

    /** Variante synchrone (thread worker bureau). */
    public String completeEphemeralSync(String prompt, long timeoutSec) throws Exception {
        return completeEphemeralSync(prompt, timeoutSec, null);
    }

    public String completeEphemeralSync(String prompt, long timeoutSec, String traceChannel)
            throws Exception {
        return completeEphemeralSync(prompt, timeoutSec, ChatSendOptions.legacy(), traceChannel);
    }

    public String completeEphemeralSync(String prompt, long timeoutSec, ChatSendOptions options,
            String traceChannel) throws Exception {
        final String[] holder = new String[1];
        final String[] err = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        completeEphemeral(prompt, new ChatBackend.OnReply() {
            @Override
            public void onLlmReply(LlmReply reply) {
                holder[0] = reply.content != null ? reply.content : "";
                latch.countDown();
            }

            @Override
            public void onReply(String text) {
                holder[0] = text;
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                err[0] = error;
                latch.countDown();
            }
        }, options, traceChannel);
        if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
            throw new RuntimeException("Délai dépassé");
        }
        if (err[0] != null) throw new RuntimeException(err[0]);
        if (holder[0] == null) throw new RuntimeException("Pas de réponse");
        return holder[0];
    }
}
