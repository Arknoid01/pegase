package com.pegasuscorp.orbe.chat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
    private static final int MAX_TURNS = 8;
    private static final int MAX_TURN_CHARS = 700;
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
        return "Local/" + LocalLlmServer.MODEL_LABEL;
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback) {
        send(history, userMessage, callback, ChatSendOptions.legacy());
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback,
            ChatSendOptions options) {
        final List<Turn> hist = history != null ? new ArrayList<>(history) : new ArrayList<>();
        io.execute(() -> {
            try {
                String reply = reply(hist, userMessage, callback);
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

    private String reply(List<Turn> history, String userMessage, OnReply callback) throws Exception {
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
        String user = userMessage != null ? userMessage.trim() : "";
        String lastAssistant = lastAssistantText(history);

        String web = null;
        if (!SMALL_TALK.matcher(user).matches() && user.length() > 3) {
            String query = webQuery(server, user, lastAssistant);
            if (query != null) web = runSearch(query, user);
        }

        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", systemPrompt()));
        List<Turn> recent = recentTurns(history);
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
        if (web != null) {
            u.append("[Ce que TA recherche web vient de trouver (pas dit par ")
                    .append(UserProfileStore.getInstance(app).getUserName())
                    .append(") : ").append(web).append("]\n");
        }
        u.append(user);
        if (lastRole.equals("user")) {
            JSONObject prev = msgs.getJSONObject(msgs.length() - 1);
            prev.put("content", prev.getString("content") + "\n" + u);
        } else {
            msgs.put(new JSONObject().put("role", "user").put("content", u.toString()));
        }

        JSONObject body = new JSONObject()
                .put("messages", msgs)
                .put("temperature", web != null ? 0.4 : 0.7)
                .put("top_p", 0.9)
                .put("max_tokens", 450)
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
        Log.i(TAG, "réponse " + (System.currentTimeMillis() - t0) + " ms, web=" + (web != null));
        String out = PegasePrompt.sanitizeForDisplay(full).trim();
        if (out.isEmpty()) throw new IllegalStateException("réponse vide");
        return out;
    }

    private String systemPrompt() {
        UserProfileStore p = UserProfileStore.getInstance(app);
        String now = new SimpleDateFormat("EEEE d MMMM yyyy, HH'h'mm", Locale.FRENCH)
                .format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("Tu es ").append(p.getAssistantName()).append(", l'assistant personnel de ")
                .append(p.getUserName()).append(", sur son téléphone. Tu le tutoies. ")
                .append("Personnalité : ").append(p.getAssistantPersonality()).append('\n')
                .append("Réponds en français, comme à l'oral, en 2 à 5 phrases, sans listes ni ")
                .append("markdown ni emojis. Reste sur ce que ").append(p.getUserName())
                .append(" vient de dire et ne contredis pas ce qu'il affirme sur sa propre vie. ")
                .append("Quand un bloc entre crochets donne ce que ta recherche web a trouvé, c'est ")
                .append("toi qui l'as cherché (dis « d'après ce que j'ai trouvé ») : appuie-toi dessus ")
                .append("et n'invente aucun chiffre, nom ni fait qui n'y figure pas ; si tu ne sais ")
                .append("pas, dis-le simplement. Tu ne peux pas agir (réveil, message…) dans cette réponse : ")
                .append("s'il le demande, dis-lui de le formuler comme une commande.\n");
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
                    out[0] = result != null ? result.text : null;
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
        return out[0] != null ? clip(out[0], 1500) : null;
    }

    private static String lastAssistantText(List<Turn> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Turn t = history.get(i);
            if (!t.fromUser && !t.system && !t.text.isEmpty()) return t.text;
        }
        return null;
    }

    private static List<Turn> recentTurns(List<Turn> history) {
        int start = Math.max(0, history.size() - MAX_TURNS);
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
}
