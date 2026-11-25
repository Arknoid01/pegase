package com.pegasuscorp.orbe.chat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.pegasuscorp.orbe.chatbot.ChatbotEngine;
import com.pegasuscorp.orbe.chatbot.ChatbotStore;
import com.pegasuscorp.orbe.chatbot.ChatbotVoice;
import com.pegasuscorp.orbe.llm.LocalLlmServer;
import com.pegasuscorp.orbe.llm.PegasePrompt;
import com.pegasuscorp.orbe.memory.UserProfileStore;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolRegistry;
import com.pegasuscorp.orbe.tools.ToolResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Discussion 100 % téléphone : le modèle local ({@link LocalLlmServer}, Gemma 4 E2B) répond,
 * sans outils natifs (les petits modèles les appellent mal : 9/14 au banc), avec :
 * <ol>
 *   <li>un prompt court (personnalité, prénom, date) : la lecture du prompt coûte ~30 jetons/s ;</li>
 *   <li>les derniers tours de la conversation ;</li>
 *   <li>une décision « recherche web ou non » contrainte par grammaire, puis le moteur de
 *       recherche privé d'Orbe (outil {@code search}) dont le résultat est joint au message.</li>
 * </ol>
 * Les commandes (réveil, minuteur, agenda…) restent au {@link com.pegasuscorp.orbe.llm.LocalCommandRouter}.
 */
public final class LocalServerChatBackend implements ChatBackend {

    private static final String TAG = "LocalChat";
    private static final int MAX_WINDOW = 12;
    private static final int KEEP_AFTER_JUMP = 6;
    private static final int MAX_TURN_CHARS = 700;
    /** Budget prompt discussion (slot 4096 jetons ≈ 12–14k car. utiles). */
    private static final int CHAT_PROMPT_MAX_CHARS = 13_000;
    private static final int REPLY_MAX_TOKENS_SHORT = 220;
    private static final int REPLY_MAX_TOKENS_WEB = 280;
    /** Partage de vie, émotion, doute — un peu plus de place pour empathie + relance. */
    private static final int REPLY_MAX_TOKENS_EMOTION = 420;
    private static final int REPLY_MAX_TOKENS_EXPLAIN = 520;
    private static final int REPLY_MAX_TOKENS_DOCUMENT = 640;
    /** Tour personnel / émotionnel (hors petites phrases d'accueil). */
    static final Pattern EMOTIONAL_TURN = Pattern.compile(
            "\\b(j'ai peur|je stress|j'en ai (assez|marre)|ça me (fait|stress|inqui)|"
                    + "triste|content|heureux|déçu|déprime|inquiet|angoiss|émotion|"
                    + "si jamais|j'espère (pas|que)|je pense (arrêter|changer|quitter)|"
                    + "dernier .{0,25}(noël|fois)|plus en tant que|pré.?commande|"
                    + "c'est un pari|dur pour moi|pas facile pour moi|reconversion)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern EXPLAIN_ASK = Pattern.compile(
            "\\b(explique|expliques|d[ée]taille|d[ée]veloppe|raconte|en d[ée]tail|"
                    + "comment [çc]a marche|pourquoi .* en profondeur|"
                    + "r[ée]sume|r[ée]sume-moi|synth[èe]se|synth[ée]tise)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCUMENT_ATTACHED = Pattern.compile(
            "---\\s*\\nDocument joint", Pattern.CASE_INSENSITIVE);
    private static final int REPLY_TIMEOUT_MS = 240_000;
    /** Petites phrases sans besoin de web (évite une décision de ~3 s). */
    private static final Pattern SMALL_TALK = Pattern.compile(
            "^(salut|coucou|bonjour|bonsoir|hello|merci|ok|okay|d'accord|top|super|cool|génial|"
                    + "bonne nuit|à plus|a plus|ça va|ca va|oui|non|lol|mdr|haha)\\b.{0,25}$",
            Pattern.CASE_INSENSITIVE);

    private final Context app;
    private final ToolRegistry registry = new ToolRegistry();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public LocalServerChatBackend(Context context) {
        this.app = context.getApplicationContext();
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public String traceBackendLabel() {
        return "Local/" + LocalLlmServer.get(app).engineLabel();
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback) {
        send(history, userMessage, callback, ChatSendOptions.legacy());
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback,
            ChatSendOptions options) {
        final List<Turn> hist = history != null ? new ArrayList<>(history) : new ArrayList<>();
        // Sans historique = tâche de fond (résumé de session, tri des spams SMS, éditeur de
        // mémoire, traduction…) : réponse BRUTE. Surtout pas de recherche web (le texte d'un SMS
        // partirait chez Brave), ni personnalité, ni souvenirs, ni limite de 2-3 phrases.
        // La discussion passe toujours par ConversationManager, dont l'historique contient au
        // moins le tour en cours.
        final boolean raw = hist.isEmpty();
        io.execute(() -> {
            try {
                String reply = raw ? rawReply(userMessage) : reply(hist, userMessage, callback);
                main.post(() -> callback.onReply(reply));
            } catch (Exception e) {
                Log.w(TAG, "échec", e);
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                main.post(() -> callback.onError("Modèle local : " + msg));
            }
        });
    }

    /** Boucle agentique non gérée en local : on rend le dernier résultat d'outil tel quel. */
    @Override
    public void sendAgenticContinuation(AgenticChain chain, ChatSendOptions options,
            OnReply callback) {
        if (chain != null && callback != null) {
            callback.onLlmReply(LlmReply.text(chain.lastToolResult()));
        }
    }

    /** Plafond d'un prompt de tâche de fond (≈3 300 jetons, slot de 4 096). */
    static final int RAW_MAX_CHARS = 11_000;

    /** Tâche de fond : le prompt tel quel, emplacement dédié, sans flux. */
    private String rawReply(String prompt) throws Exception {
        LocalLlmServer server = LocalLlmServer.get(app);
        if (!server.isAvailable()) {
            throw new IllegalStateException("le modèle " + LocalLlmServer.MODEL_LABEL
                    + " n'est pas installé (Réglages → Cerveau)");
        }
        if (!server.ensureStarted()) throw new IllegalStateException("démarrage impossible");
        JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "user")
                .put("content", clipRaw(prompt)));
        JSONObject body = new JSONObject()
                .put("messages", msgs)
                .put("temperature", 0.2)
                .put("max_tokens", 700)
                .put("id_slot", LocalLlmServer.BACKGROUND_SLOT)
                .put("cache_prompt", true)
                .put("chat_template_kwargs", new JSONObject().put("enable_thinking", false));
        long t0 = System.currentTimeMillis();
        JSONObject r = server.chat(body, REPLY_TIMEOUT_MS);
        String out = r.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "").trim();
        Log.i(TAG, "tâche de fond " + (System.currentTimeMillis() - t0) + " ms, "
                + (prompt != null ? prompt.length() : 0) + " car.");
        if (out.isEmpty()) throw new IllegalStateException("réponse vide");
        return out;
    }

    /** Garde le début (consignes) et la fin (le plus récent) d'un prompt trop long. */
    static String clipRaw(String prompt) {
        if (prompt == null) return "";
        if (prompt.length() <= RAW_MAX_CHARS) return prompt;
        int head = RAW_MAX_CHARS / 3;
        int tail = RAW_MAX_CHARS - head - 20;
        return prompt.substring(0, head) + "\n[…]\n" + prompt.substring(prompt.length() - tail);
    }

    private String reply(List<Turn> history, String userMessage, OnReply callback) throws Exception {
        String user = userMessage != null ? userMessage.trim() : "";
        // Chatbot hybride d'abord (règles + banque par sens + fiches, ≈15 ms) : une réponse
        // connue sort tout de suite, une idée est reformulée par l'E2B (plan-chatbot-hybride.md).
        ChatbotEngine bot = null;
        ChatbotEngine.Decision cb = null;
        if (chatbotEligible(user)) {
            bot = ChatbotStore.of(app).get();
            if (bot != null) {
                cb = bot.decide(user);
                Log.i(TAG, "chatbot " + cb + (cb.ideas.isEmpty() ? "" : " idées=" + cb.ideas.size()));
                if ("forte".equals(cb.zone) && !cb.reply.isEmpty()) {
                    lastWebQuery = null;
                    return cb.reply;
                }
            }
        }
        LocalLlmServer server = LocalLlmServer.get(app);
        if (!server.isAvailable()) {
            throw new IllegalStateException("le modèle " + LocalLlmServer.MODEL_LABEL
                    + " n'est pas installé (Réglages → Cerveau)");
        }
        if (!server.ensureStarted()) throw new IllegalStateException("démarrage impossible");

        // L'historique fourni se termine par le tour utilisateur en cours : on le remplace
        // par userMessage (qui peut porter des consignes internes).
        if (!history.isEmpty() && history.get(history.size() - 1).fromUser) {
            history.remove(history.size() - 1);
        }
        String lastAssistant = lastAssistantText(history);

        final boolean reformulate = cb != null && "moyenne".equals(cb.zone) && !cb.ideas.isEmpty();

        String web = null;
        int need = reformulate ? WEB_NO : webNeed(user);
        String query;
        String question = user;
        if (need == WEB_ASK && lastWebQuery != null && FOLLOW_UP.matcher(user).find()) {
            // « des explications à ça ? » juste après une recherche : même sujet, nouvel angle.
            // Le moteur cherche avec la QUESTION : elle doit porter le sujet, pas « ça ».
            query = lastWebQuery + " — " + user;
            question = query;
        } else {
            query = need == WEB_YES ? user
                    : need == WEB_ASK ? webQuery(server, user, lastAssistant) : null;
        }
        if (query != null) web = runSearch(query, question);
        lastWebQuery = web != null ? query : null;
        // Politesse courte (bonjour, merci…) : on repart de zéro, sans souvenirs ni vieux sujet
        boolean social = isShortSocial(user);
        String memories = social || reformulate ? null : memoriesFor(user);
        boolean emotional = !reformulate && looksEmotionalTurn(user);

        JSONArray msgs = new JSONArray();
        // Reformulation : prompt court et 3 échanges (le grand prompt faisait broder l'E2B :
        // faits ajoutés, 4-6 phrases). LiteRT repart d'une conversation neuve à chaque appel,
        // changer de système ne coûte rien.
        msgs.put(new JSONObject().put("role", "system").put("content", reformulate
                ? ChatbotVoice.persona(UserProfileStore.getInstance(app).getUserName())
                : systemPrompt()));
        List<Turn> recent = reformulate ? lastTurns(history, 6)
                : social ? lastTurns(history, 2) : recentTurns(history);
        StringBuilder pendingSystem = new StringBuilder();
        String lastRole = "system";
        for (Turn t : recent) {
            if (t.system) {      // indice d'outil : rattaché au prochain message utilisateur
                pendingSystem.append('[').append(clip(t.text)).append("]\n");
                continue;
            }
            String role = t.fromUser ? "user" : "assistant";
            String text = clip(t.text);
            if (t.fromUser && pendingSystem.length() > 0) {
                text = pendingSystem + text;
                pendingSystem.setLength(0);
            }
            if (role.equals(lastRole)) {          // les gabarits exigent l'alternance
                JSONObject prev = msgs.getJSONObject(msgs.length() - 1);
                prev.put("content", prev.getString("content") + "\n" + text);
            } else {
                if (lastRole.equals("system") && role.equals("assistant")) {
                    msgs.put(new JSONObject().put("role", "user").put("content", "…"));
                }
                msgs.put(new JSONObject().put("role", role).put("content", text));
                lastRole = role;
            }
        }
        StringBuilder u = new StringBuilder(pendingSystem);
        if (memories != null) {
            u.append("[Tes souvenirs sur ").append(UserProfileStore.getInstance(app).getUserName())
                    .append(", à utiliser seulement si c'est vraiment pertinent pour ce message : ")
                    .append(memories).append("]\n");
        }
        if (emotional && web == null) {
            u.append("[Tour personnel ou émotionnel : prends ton temps, 4 à 6 phrases, ")
                    .append("réagis à ce qu'il vient de dire, relance avec une question courte ")
                    .append("ou une invitation à en dire plus.]\n");
        }
        if (web != null) {
            u.append("[Ce que TA recherche web vient de trouver (pas dit par ")
                    .append(UserProfileStore.getInstance(app).getUserName())
                    .append(") : ").append(web).append("]\n");
        }
        // Pas d'heure ici ni dans le système : tout ce qui change d'un message à l'autre casse
        // le cache du serveur (≈600 jetons relus, ≈12–15 s). La date du jour suffit.
        if (cb != null && !reformulate && web == null) {
            u.append(ChatbotVoice.factsLine(cb.facts));     // zone faible : faits des fiches
        }
        user = fitUserMessage(user, estimatePromptChars(msgs, u, memories, web));
        if (reformulate) {
            u.append(ChatbotVoice.ideasPrompt(
                    UserProfileStore.getInstance(app).getUserName(), user, cb));
        } else {
            u.append(user);
        }
        if (lastRole.equals("user")) {
            JSONObject prev = msgs.getJSONObject(msgs.length() - 1);
            prev.put("content", prev.getString("content") + "\n" + u);
        } else {
            msgs.put(new JSONObject().put("role", "user").put("content", u.toString()));
        }

        JSONObject body = new JSONObject()
                .put("messages", msgs)
                .put("temperature", reformulate ? ChatbotVoice.TEMP_IDEES
                        : web != null ? 0.4 : (emotional ? 0.78 : 0.7))
                .put("top_p", 0.9)
                .put("max_tokens", reformulate ? ChatbotVoice.MAX_TOKENS_IDEES
                        : maxReplyTokens(user, web != null))
                .put("id_slot", LocalLlmServer.CHAT_SLOT)
                .put("cache_prompt", true)
                .put("chat_template_kwargs", new JSONObject().put("enable_thinking", false));
        long t0 = System.currentTimeMillis();
        String full = server.chatStream(body, REPLY_TIMEOUT_MS, acc -> {
            if (callback instanceof StreamOnReply) {
                String shown = PegasePrompt.sanitizeForDisplay(acc);
                main.post(() -> ((StreamOnReply) callback).onPartial(shown));
            }
        });
        Log.i(TAG, "réponse " + (System.currentTimeMillis() - t0) + " ms, web=" + (web != null)
                + ", souvenirs=" + (memories != null)
                + " · " + LocalLlmServer.describeTimings(server.lastTimings));
        String out = PegasePrompt.sanitizeForDisplay(full).trim();
        if (cb != null) {
            out = ChatbotVoice.cleanEmojis(out);
            // Garde-fou : un nombre ou un nom propre de l'idée a disparu → l'idée brute.
            // Garde-fou inverse : un nom propre ou un nombre qui ne vient de nulle part = inventé.
            if (reformulate && !out.equals(cb.ideas.get(0))) {
                StringBuilder provided = new StringBuilder(user).append('\n')
                        .append(String.join("\n", cb.ideas)).append('\n')
                        .append(String.join("\n", cb.facts)).append('\n')
                        .append(UserProfileStore.getInstance(app).getUserName());
                for (Turn t : lastTurns(history, 6)) if (t.text != null) provided.append('\n').append(t.text);
                if (ChatbotVoice.addedEssentials(out, provided.toString())) {
                    Log.i(TAG, "chatbot : fait ajouté par l'E2B, idée servie telle quelle");
                    out = cb.ideas.get(0);
                }
            }
            bot.rememberSpoken(out);
        }
        if (out.isEmpty()) throw new IllegalStateException("réponse vide");
        return out;
    }

    /** Message ordinaire tapé ou dicté (pas de document joint, pas de consigne interne). */
    static boolean chatbotEligible(String user) {
        return user != null && !user.isEmpty() && user.length() <= 300 && !user.startsWith("[")
                && !DOCUMENT_ATTACHED.matcher(user).find();
    }

    static final int WEB_NO = 0, WEB_YES = 1, WEB_ASK = 2;
    /** Requête de la dernière recherche réussie (relances « et pourquoi ? »). */
    private volatile String lastWebQuery;
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "\\b(explications?|pourquoi|comment [cç]a|plus de d[ée]tails|en savoir plus|"
                    + "et (lui|elle|eux|apr[eè]s)|quoi d'autre|c'est vrai|depuis quand|la suite)\\b",
            Pattern.CASE_INSENSITIVE);
    /** Demandes de faits frais sans ambiguïté : recherche directe, sans décision du modèle. */
    private static final Pattern WEB_SURE = Pattern.compile(
            "\\b(prix|tarifs?|combien co[uû]te|cours de|en bourse|r[ée]sultats?|score|classement|"
                    + "qui a gagn[ée]|vainqueur|actualit[ée]s?|news|derni[eè]res? nouvelles|"
                    + "horaires?|ouvert ce|programme t[ée]l[ée]|sorti[e]? quand|date de sortie|"
                    + "qu'a dit|qu'est-ce qu'a dit|d[ée]claration)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUESTION = Pattern.compile(
            "\\?|^(qui|que|quoi|quel|quelle|quels|quelles|quand|o[uù]|comment|pourquoi|combien|"
                    + "est-ce|c'est quoi|tu sais|tu connais|dis-moi|explique|raconte|on a des|"
                    + "il y a|y a-t-il)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Oui (fait frais évident) / non (pas une question, petite phrase) / à décider par le modèle. */
    static int webNeed(String user) {
        String u = user == null ? "" : user.trim();
        if (u.length() <= 3 || SMALL_TALK.matcher(u).matches()) return WEB_NO;
        if (WEB_SURE.matcher(u).find()) return WEB_YES;
        if (!QUESTION.matcher(u).find()) return WEB_NO;      // affirmation, émotion, opinion
        return WEB_ASK;
    }

    /** Précharge la consigne de discussion dans son slot (ouverture du chat, hors UI). */
    public static void prewarm(Context ctx) {
        ChatbotStore.of(ctx).get();   // banque + empreintes (≈1-7 s la 1re fois)
        try {
            LocalLlmServer server = LocalLlmServer.get(ctx);
            if (!server.isAvailable() || !server.ensureStarted()) return;
            LocalServerChatBackend b = new LocalServerChatBackend(ctx);
            JSONArray m = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", b.systemPrompt()))
                    .put(new JSONObject().put("role", "user").put("content", "…"));
            server.chat(new JSONObject().put("messages", m).put("max_tokens", 1)
                    .put("id_slot", LocalLlmServer.CHAT_SLOT).put("cache_prompt", true)
                    .put("chat_template_kwargs", new JSONObject().put("enable_thinking", false)),
                    120_000);
            b.webQuery(server, "tu as vu le match d'hier ?", null);   // exemples de la décision en cache
        } catch (Exception e) {
            Log.w(TAG, "préchauffage : " + e.getMessage());
        }
    }

    private String systemPrompt() {
        UserProfileStore p = UserProfileStore.getInstance(app);
        String now = new SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRENCH).format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("Tu es ").append(p.getAssistantName()).append(", l'assistant personnel de ")
                .append(p.getUserName()).append(", sur son téléphone. Tu le tutoies. ")
                .append("Personnalité : ").append(p.getAssistantPersonality()).append('\n')
                .append("Réponds en français, comme à l'oral. Fait simple : 2 ou 3 phrases courtes ; ")
                .append("partage de vie, émotion ou doute : 4 à 6 phrases chaleureuses, empathie, ")
                .append("et souvent une question ou une relance en fin pour continuer la discussion. ")
                .append("Un peu plus long si besoin (explications détaillées sur demande). Sans listes, ")
                .append("markdown ni emojis. Reste sur ce que ").append(p.getUserName())
                .append(" vient de dire et ne contredis pas ce qu'il affirme sur sa propre vie. ")
                .append("Quand un bloc entre crochets donne ce que ta recherche web a trouvé, c'est ")
                .append("toi qui l'as cherché (dis « d'après ce que j'ai trouvé ») : appuie-toi dessus ")
                .append("et n'invente aucun chiffre, nom ni fait qui n'y figure pas ; si tu ne sais ")
                .append("pas, dis-le simplement. Recopie les nombres exactement comme dans la source ")
                .append("(« 2,126 € », pas « 2126 euros »). Tu es une IA : tu n'as ni journée, ni ")
                .append("activités, ni souvenirs personnels ; ne t'invente jamais de vie ou ")
                .append("d'expérience, ramène plutôt la conversation sur lui. Ce que tu sais de lui ")
                .append("vient seulement de cette consigne et des souvenirs entre crochets : cite un souvenir ")
                .append("seulement s'il colle vraiment au sujet du moment (sinon ignore-le). S'il te ")
                .append("demande si tu te souviens de quelque chose qui n'y est pas, dis honnêtement ")
                .append("que tu ne sais pas et propose qu'il te le dise. Tu ne peux pas agir (réveil, message, brief, "
                + "sms, F1…) dans cette réponse : ne dis JAMAIS « c'est fait » ou « je l'ai noté » pour une action ; ")
                .append("propose plutôt une commande claire (« mets un réveil à 7h », « brief du matin », …).\n");
        List<String> projects = p.getProjectsList();
        if (projects != null && !projects.isEmpty()) {
            sb.append("Ses projets : ").append(String.join(", ",
                    projects.subList(0, Math.min(6, projects.size())))).append(".\n");
        }
        List<String> interests = p.getInterestsList();
        if (interests != null && !interests.isEmpty()) {
            sb.append("Ses centres d'intérêt : ").append(String.join(", ",
                    interests.subList(0, Math.min(6, interests.size())))).append(".\n");
        }
        sb.append("Nous sommes le ").append(now).append('.');
        return sb.toString();
    }

    /**
     * Décision contrainte par grammaire : « non » ou « web &lt;requête&gt; ». ~2–4 s à chaud.
     * @return la requête, ou null si pas de recherche.
     */
    private String webQuery(LocalLlmServer server, String user, String lastAssistant) {
        try {
            String sys = "Tu décides si la dernière phrase de l'utilisateur demande une information "
                    + "à vérifier sur le web : actualité, sport, résultats, personnes, entreprises, "
                    + "bourse, prix, horaires, lieux, déclarations, chiffres, événements récents. "
                    + "Réponds « web » suivi d'une requête courte avec les noms propres (complète "
                    + "avec le contexte si la phrase y fait référence), ou « non » pour une "
                    + "discussion, une opinion, une émotion, une blague ou une question sur lui.";
            JSONArray m = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", sys))
                    .put(new JSONObject().put("role", "user").put("content", "salut, ça va ?"))
                    .put(new JSONObject().put("role", "assistant").put("content", "non"))
                    .put(new JSONObject().put("role", "user").put("content",
                            "qui a gagné le dernier match du PSG ?"))
                    .put(new JSONObject().put("role", "assistant").put("content",
                            "web résultat dernier match PSG"))
                    .put(new JSONObject().put("role", "user").put("content",
                            "j'en ai marre de ma journée"))
                    .put(new JSONObject().put("role", "assistant").put("content", "non"))
                    .put(new JSONObject().put("role", "user").put("content", "qui es-tu ?"))
                    .put(new JSONObject().put("role", "assistant").put("content", "non"))
                    .put(new JSONObject().put("role", "user").put("content",
                            "(Contexte, ta réponse précédente : « Le cours d'Airbus a chuté de 8 % "
                                    + "cette semaine. »)\nil s'est passé quoi ?"))
                    .put(new JSONObject().put("role", "assistant").put("content",
                            "web raison chute action Airbus cette semaine"));
            String content = (lastAssistant != null
                    ? "(Contexte, ta réponse précédente : « " + clip(lastAssistant, 300) + " »)\n"
                    : "") + user;
            m.put(new JSONObject().put("role", "user").put("content", content));
            JSONObject body = new JSONObject()
                    .put("messages", m)
                    .put("grammar", "root ::= \"non\" | \"web \" [^\\n]{3,90}\n")
                    .put("regex", "non|web [^\\n]{3,90}")
                    .put("temperature", 0.1)
                    .put("max_tokens", 32)
                    .put("id_slot", LocalLlmServer.WEB_DECISION_SLOT)
                    .put("cache_prompt", true)
                    .put("chat_template_kwargs", new JSONObject().put("enable_thinking", false));
            long t0 = System.currentTimeMillis();
            JSONObject r = server.chat(body, 60_000);
            String line = r.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "").trim();
            Log.i(TAG, "décision web " + (System.currentTimeMillis() - t0) + " ms : "
                    + (line.startsWith("web") ? "oui" : "non"));
            if (!line.startsWith("web ")) return null;
            String q = line.substring(4).trim();
            return q.isEmpty() ? null : q;
        } catch (Exception e) {
            Log.w(TAG, "décision web : " + e.getMessage());
            return null;
        }
    }

    /** Outil {@code search} d'Orbe (moteur privé, Tavily en secours, filtre de confidentialité). */
    private String runSearch(String query, String question) {
        Tool tool = registry.findById("search");
        if (tool == null) return null;
        final String[] out = {null};
        CountDownLatch done = new CountDownLatch(1);
        try {
            JSONObject params = new JSONObject().put("query", query).put("question", question);
            main.post(() -> tool.execute(app, params, new ToolCallback() {
                @Override public void onSuccess(ToolResult result) {
                    out[0] = result == null ? null : compactSearch(result);
                    done.countDown();
                }
                @Override public void onConfirmNeeded(String q, Runnable ok, Runnable cancel) {
                    done.countDown();
                }
                @Override public void onError(String error) {
                    out[0] = "(recherche impossible : " + error + ")";
                    done.countDown();
                }
            }));
            done.await(45, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
        return out[0];
    }

    /**
     * Réponse extraite + passages sourcés (contexte LLM du moteur), sans les consignes du
     * cloud, plafonné : chaque jeton du prompt coûte ~25 ms à lire sur le téléphone.
     */
    static String compactSearch(ToolResult r) {
        String ctx = r.llmContext;
        if (ctx == null || ctx.trim().isEmpty()) return clip(r.text, 600);
        StringBuilder sb = new StringBuilder();
        for (String line : ctx.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("[") || l.startsWith("Question :")) continue;
            if (l.startsWith("Source ")) l = clip(l, 350);
            sb.append(l).append(' ');
            if (sb.length() > 1100) break;
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? clip(r.text, 600) : clip(out, 1200);
    }

    /** Jusqu'à 3 souvenirs pertinents (recherche sémantique locale, MiniLM), ou null. */
    private String memoriesFor(String user) {
        try {
            List<com.pegasuscorp.orbe.memory.MemoryEntry> mem =
                    com.pegasuscorp.orbe.memory.MemoryRepository.getInstance(app)
                            .getRelevantMemoriesSemantic(user, new ArrayList<>(), new ArrayList<>(), 3,
                                    com.pegasuscorp.orbe.memory.MemoryRepository.SEMANTIC_MIN_SCORE + 0.06f);
            if (mem == null || mem.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (com.pegasuscorp.orbe.memory.MemoryEntry m : mem) {
                if (m.content == null || m.content.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append(" ; ");
                sb.append(clip(m.content, 220));
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "souvenirs : " + e.getMessage());
            return null;
        }
    }

    private static String lastAssistantText(List<Turn> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Turn t = history.get(i);
            if (!t.fromUser && !t.system && !t.text.isEmpty()) return t.text;
        }
        return null;
    }

    /** Premier tour gardé (texte), stable d'un message à l'autre tant que possible. */
    private String anchorText;

    /**
     * Fenêtre d'historique par PALIERS : même point de départ tant qu'on reste sous
     * {@link #MAX_WINDOW} tours, puis saut aux {@link #KEEP_AFTER_JUMP} derniers. Une fenêtre
     * qui glisse d'un tour à chaque message changerait le début du prompt → tout relu.
     */
    /** Les {@code n} derniers tours seulement (sans toucher à la fenêtre par paliers). */
    private static List<Turn> lastTurns(List<Turn> history, int n) {
        int start = Math.max(0, history.size() - n);
        return new ArrayList<>(history.subList(start, history.size()));
    }

    /** Message de politesse court : « bonjour », « merci », « ok ça va ? »… (pas une vraie question). */
    static boolean isShortSocial(String user) {
        if (user == null) return false;
        String t = user.trim().toLowerCase(java.util.Locale.FRENCH);
        if (t.isEmpty() || t.length() > 30) return false;
        return t.matches("^(bonjour|bonsoir|salut|hello|coucou|hey|merci|merci beaucoup|ok|okay|d'accord|"
                + "oui|non|super|top|parfait|ça va|ca va|bonne nuit|à plus|a plus)"
                + "( (ça va|ca va|toi|vous|à toi|à vous|pégase|pegase))?[ !.?,…]*$");
    }

    private synchronized List<Turn> recentTurns(List<Turn> history) {
        int start = -1;
        if (anchorText != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i).text.equals(anchorText)) { start = i; break; }
            }
        }
        if (start < 0 || history.size() - start > MAX_WINDOW) {
            start = Math.max(0, history.size() - KEEP_AFTER_JUMP);
        }
        anchorText = start < history.size() ? history.get(start).text : null;
        return new ArrayList<>(history.subList(start, history.size()));
    }

    private static String clip(String s) {
        return clip(s, MAX_TURN_CHARS);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    static boolean looksEmotionalTurn(String user) {
        if (user == null || user.trim().isEmpty()) return false;
        String t = user.trim();
        if (SMALL_TALK.matcher(t).matches()) return false;
        if (t.length() < 12) return false;
        return EMOTIONAL_TURN.matcher(t).find();
    }

    static int maxReplyTokens(String user, boolean hasWeb) {
        if (user != null && DOCUMENT_ATTACHED.matcher(user).find()) return REPLY_MAX_TOKENS_DOCUMENT;
        if (user != null && EXPLAIN_ASK.matcher(user).find()) return REPLY_MAX_TOKENS_EXPLAIN;
        if (user != null && looksEmotionalTurn(user)) return REPLY_MAX_TOKENS_EMOTION;
        if (hasWeb) return REPLY_MAX_TOKENS_WEB;
        return REPLY_MAX_TOKENS_SHORT;
    }

    static int estimatePromptChars(JSONArray msgs, StringBuilder prefix, String memories, String web) {
        int n = prefix.length();
        if (memories != null) n += memories.length();
        if (web != null) n += web.length();
        try {
            for (int i = 0; i < msgs.length(); i++) {
                n += msgs.getJSONObject(i).optString("content", "").length();
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    /** Tronque un message utilisateur trop long pour tenir dans le slot 4096. */
    public static String fitUserMessage(String user, int usedChars) {
        if (user == null) return "";
        int budget = CHAT_PROMPT_MAX_CHARS - usedChars - 400;
        if (budget < 1500) budget = 1500;
        if (user.length() <= budget) return user;
        int head = budget / 3;
        int tail = budget - head - 24;
        return user.substring(0, head) + "\n[… texte raccourci …]\n"
                + user.substring(user.length() - tail);
    }
}
