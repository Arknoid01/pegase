package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.chat.ChatBackendFactory;
import com.pegasuscorp.orbe.memory.MemoryEditResult;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apprend les corrections de prononciation et de vitesse depuis la voix.
 *
 * Phrases reconnues directement (sans LLM) :
 *   "dis Qwen comme Couène"
 *   "prononce GitHub comme Guite Hub"
 *   "Qwen ça se prononce Couène"
 *   "Qwen se dit Couène"
 *   "vitesse à 85 %"
 *   "85 % c'est mieux" / "mets la vitesse à 85"
 *   "parle plus lentement" / "parle plus vite"
 *
 * Phrases renvoyées au LLM :
 *   "tu prononces mal Qwen"  → le LLM propose une phonétique
 *   "ta voix est trop rapide"
 */
public final class SpeechRulesEditor {

    public interface Callback {
        void onResult(MemoryEditResult result);
    }

    // ── Patterns directs (mot → prononciation) ──────────────────────────────

    /** "dis X comme Y" / "prononce X comme Y" */
    private static final Pattern P_DIS_COMME = Pattern.compile(
            "(?i)(?:dis|prononce)\\s+(.+?)\\s+comme\\s+(.+)");

    /** "X ça se prononce Y" / "X se prononce Y" */
    private static final Pattern P_SE_PRONONCE = Pattern.compile(
            "(?i)(.+?)\\s+(?:ça\\s+)?se\\s+prononce\\s+(.+)");

    /** "X se dit Y" / "X ça se dit Y" */
    private static final Pattern P_SE_DIT = Pattern.compile(
            "(?i)(.+?)\\s+(?:ça\\s+)?se\\s+dit\\s+(.+)");

    /** "X c'est prononcé Y" */
    private static final Pattern P_EST_PRONONCE = Pattern.compile(
            "(?i)(.+?)\\s+c'est\\s+(?:prononcé|dit)\\s+(.+)");

    // ── Patterns vitesse ─────────────────────────────────────────────────────

    /** "vitesse à 85 %" / "mets la vitesse à 85" / "85 % c'est mieux" / "87 % est mieux" */
    private static final Pattern P_SPEED = Pattern.compile(
            "(?i)(?:vitesse\\s+(?:à|a)\\s+|mets?\\s+(?:la\\s+)?vitesse\\s+(?:à|a)\\s+)?(\\d{2,3})\\s*%\\s*(?:c'?est|est)?\\s*(?:mieux|bien|parfait|ok)?");

    // ── Mots-clés LLM ────────────────────────────────────────────────────────

    /** Déclencheurs qui méritent une analyse LLM (prononciation incorrecte signalée) */
    private static final String[] LLM_TRIGGERS = {
            "prononces mal", "prononce mal", "dis mal",
            "mauvaise prononciation", "mal prononcé",
            "ça sonne pas bien", "ça sonne mal",
            "corrige la prononciation", "corrige ta prononciation"
    };

    /** Déclencheurs vitesse sans chiffre (LLM choisit) */
    private static final String[] SPEED_TRIGGERS = {
            "parle plus lentement", "parle moins vite", "ralentis",
            "parle plus vite", "accélère", "accélère la voix", "plus rapide",
            "trop rapide", "trop lent", "ta voix est trop",
            "vitesse de ta voix", "vitesse de la voix"
    };

    private final Context appContext;
    private final SpeechRulesStore rules;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public SpeechRulesEditor(Context context) {
        appContext = context.getApplicationContext();
        rules = SpeechRulesStore.getInstance(appContext);
    }

    /**
     * Vérifie si la phrase ressemble à une correction vocale.
     * Volontairement strict pour éviter les faux positifs sur des phrases normales.
     */
    public static boolean looksLikeSpeechRuleEdit(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String t = text.toLowerCase(Locale.ROOT);

        // Patterns directs
        if (P_DIS_COMME.matcher(text).find()) return true;
        if (P_SE_PRONONCE.matcher(text).find()) return true;
        if (P_SE_DIT.matcher(text).find()) return true;
        if (P_EST_PRONONCE.matcher(text).find()) return true;

        // Vitesse avec chiffre — seulement si on voit clairement "vitesse" ou "%"
        if (t.contains("vitesse") && t.matches(".*\\d{2,3}\\s*%.*")) return true;
        if (t.matches(".*\\d{2,3}\\s*%\\s*(c'?est|est)\\s*(mieux|bien|parfait|ok).*")) return true;
        if (t.contains("mets la vitesse") || t.contains("mets la voix à")) return true;

        // Vitesse sans chiffre
        for (String trigger : SPEED_TRIGGERS) {
            if (t.contains(trigger)) return true;
        }

        // Prononciation incorrecte signalée
        for (String trigger : LLM_TRIGGERS) {
            if (t.contains(trigger)) return true;
        }

        return false;
    }

    public void process(String userText, Callback callback) {
        io.execute(() -> {
            MemoryEditResult quick = tryQuickEdit(userText);
            if (quick != null) {
                main.post(() -> callback.onResult(quick));
                return;
            }
            resolveWithLlm(userText, callback);
        });
    }

    // ── Éditions rapides (sans LLM) ──────────────────────────────────────────

    private MemoryEditResult tryQuickEdit(String text) {
        // 1. Prononciation directe
        MemoryEditResult prono = tryPronunciationPatterns(text);
        if (prono != null) return prono;

        // 2. Vitesse avec chiffre
        Matcher m = P_SPEED.matcher(text.trim());
        if (m.find() && m.group(1) != null) {
            int pct = Integer.parseInt(m.group(1));
            if (pct >= 50 && pct <= 150) {
                float speed = pct / 100f;
                rules.setSpeed(speed);
                return MemoryEditResult.applied(
                        "Vitesse : " + pct + " %",
                        "C'est noté, je parle maintenant à " + pct + " pour cent.");
            }
        }

        // 3. Vitesse sans chiffre → LLM
        return null;
    }

    private MemoryEditResult tryPronunciationPatterns(String text) {
        // "dis X comme Y" / "prononce X comme Y"
        Matcher m = P_DIS_COMME.matcher(text.trim());
        if (m.find()) return applyPronunciation(m.group(1), m.group(2));

        // "X ça se prononce Y" / "X se prononce Y"
        m = P_SE_PRONONCE.matcher(text.trim());
        if (m.find()) return applyPronunciation(m.group(1), m.group(2));

        // "X se dit Y"
        m = P_SE_DIT.matcher(text.trim());
        if (m.find()) return applyPronunciation(m.group(1), m.group(2));

        // "X c'est prononcé Y"
        m = P_EST_PRONONCE.matcher(text.trim());
        if (m.find()) return applyPronunciation(m.group(1), m.group(2));

        return null;
    }

    private MemoryEditResult applyPronunciation(String rawWord, String rawPronunciation) {
        String word = rawWord.trim();
        String pronunciation = rawPronunciation.trim();
        if (word.isEmpty() || pronunciation.isEmpty()) return null;
        // Nettoie les formules parasites
        pronunciation = pronunciation.replaceAll("(?i)^(c'est\\s+|ça\\s+fait\\s+|ça\\s+se\\s+dit\\s+)", "").trim();
        rules.putDictionary(word, pronunciation);
        return MemoryEditResult.applied(
                word + " → " + pronunciation,
                "Noté. Je prononcerai " + word + " comme " + pronunciation + ".");
    }

    // ── Résolution LLM (vitesse sans chiffre, "tu prononces mal X") ──────────

    private void resolveWithLlm(String userText, Callback callback) {
        // On envoie seulement le contexte minimal — pas toutes les règles
        float currentSpeed = rules.getSpeed();
        int currentSpeedPct = Math.round(currentSpeed * 100f);

        String prompt =
                "L'utilisateur corrige la prononciation ou la vitesse de la voix.\n"
                + "Vitesse actuelle : " + currentSpeedPct + " %\n"
                + "Demande : \"" + userText + "\"\n\n"
                + "Réponds UNIQUEMENT en JSON valide sans markdown :\n"
                + "{\n"
                + "  \"action\": \"set_speed\" | \"add_dictionary\" | \"none\",\n"
                + "  \"summary\": \"confirmation courte (max 50 car.)\",\n"
                + "  \"spoken\": \"réponse orale naturelle en français\",\n"
                + "  \"speed\": 0.85,          (si set_speed, entre 0.5 et 1.5)\n"
                + "  \"word\": \"mot concerné\",  (si add_dictionary)\n"
                + "  \"value\": \"phonétique\"    (si add_dictionary, ex: 'Couène' pour Qwen)\n"
                + "}\n"
                + "Règles :\n"
                + "- 'parle plus lentement' → set_speed avec speed < " + currentSpeed + "\n"
                + "- 'parle plus vite' → set_speed avec speed > " + currentSpeed + "\n"
                + "- 'tu prononces mal Qwen' → add_dictionary, word=Qwen, value=phonétique française\n"
                + "- Si tu ne sais pas la phonétique d'un mot, demande-le dans spoken.\n"
                + "- Réponds none si ce n'est pas une correction vocale.";

        ChatBackend backend = ChatBackendFactory.create(appContext);
        backend.send(java.util.Collections.emptyList(), prompt, new ChatBackend.OnReply() {
            @Override
            public void onReply(String text) {
                MemoryEditResult result = applyLlmPlan(text);
                main.post(() -> callback.onResult(result));
            }

            @Override
            public void onError(String error) {
                main.post(() -> callback.onResult(
                        MemoryEditResult.failed("Je n'ai pas pu mettre à jour la règle vocale.")));
            }
        });
    }

    private MemoryEditResult applyLlmPlan(String raw) {
        JSONObject plan = extractJson(raw);
        if (plan == null) return MemoryEditResult.notMemoryEdit();

        String action = plan.optString("action", "none");
        String summary = plan.optString("summary", "Règle vocale mise à jour");
        String spoken = plan.optString("spoken", "C'est noté.");

        switch (action) {
            case "set_speed": {
                double speed = plan.optDouble("speed", -1);
                if (speed < 0.5 || speed > 1.5) return MemoryEditResult.notMemoryEdit();
                rules.setSpeed((float) speed);
                return MemoryEditResult.applied(summary, spoken);
            }
            case "add_dictionary": {
                String word = plan.optString("word", "").trim();
                String value = plan.optString("value", "").trim();
                if (word.isEmpty() || value.isEmpty()) {
                    return MemoryEditResult.failed(spoken.isEmpty()
                            ? "Je n'ai pas pu trouver la phonétique, dis-moi comment prononcer le mot."
                            : spoken);
                }
                rules.putDictionary(word, value);
                return MemoryEditResult.applied(summary, spoken);
            }
            case "none":
            default:
                return MemoryEditResult.notMemoryEdit();
        }
    }

    private static JSONObject extractJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return new JSONObject(text.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }
}
