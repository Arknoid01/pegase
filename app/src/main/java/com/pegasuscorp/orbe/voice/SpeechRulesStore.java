package com.pegasuscorp.orbe.voice;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Charge et persiste {@code files/speech/speech_rules.json} (dictionnaire, vitesse, remplacements).
 */
public final class SpeechRulesStore {

    private static final float DEFAULT_SPEED = 0.87f;
    private static final String PREFS = "orbe_piper";
    private static final String KEY_SPEED_MIGRATED = "speech_rules_speed_migrated";

    /**
     * Ne jamais mettre dans le dictionnaire phonétique TTS :
     * mots fonction FR, et anglicismes seed qui écrasent le français courant
     * (« chat »→Tchate, « continue »→Conitniou, « rest »→Reste…).
     */
    private static final Set<String> BLOCKED_DICTIONARY_KEYS = Set.of(
            "dis", "dit", "moi", "toi", "lui", "elle", "nous", "vous",
            "je", "tu", "il", "ils", "on", "ce", "ça", "ca", "et", "ou",
            "de", "du", "des", "le", "la", "les", "un", "une", "au", "aux",
            "que", "qui", "ne", "pas", "plus", "comme", "prononce", "prononces",
            // Collisions FR / seed phonétique agressif
            "chat", "branch", "token", "commit", "continue", "root", "word",
            "rest", "zoom", "go", "x", "ar", "pr", "mr", "ea", "gg", "wp",
            "yc", "ms"
    );

    private static SpeechRulesStore instance;

    private final File rulesFile;
    private final Context appContext;
    private JSONObject root = new JSONObject();
    private volatile SpeechRulesSnapshot cache;

    private SpeechRulesStore(Context context) {
        appContext = context.getApplicationContext();
        File dir = new File(appContext.getFilesDir(), "speech");
        if (!dir.exists()) dir.mkdirs();
        rulesFile = new File(dir, "speech_rules.json");
        loadOrSeed();
        rebuildCache();
    }

    /**
     * Recharge le fichier depuis le disque et recompile le dictionnaire en RAM.
     * À appeler au démarrage de l'assistant (appui long).
     */
    public synchronized void warmUp() {
        reloadFromDisk();
        ensureStructure();
        if (purgeBlockedDictionaryKeys()) {
            save();
        } else {
            rebuildCache();
        }
    }

    /** Accès rapide aux règles précompilées (RAM). */
    public SpeechRulesSnapshot getSnapshot() {
        SpeechRulesSnapshot snap = cache;
        if (snap != null) return snap;
        synchronized (this) {
            if (cache == null) rebuildCache();
            return cache;
        }
    }

    public synchronized int getCachedRuleCount() {
        return cache != null ? cache.ruleCount() : 0;
    }

    public static synchronized SpeechRulesStore getInstance(Context context) {
        if (instance == null) {
            instance = new SpeechRulesStore(context);
        }
        return instance;
    }

    public synchronized String snapshotForEdit() {
        try {
            return root.toString(2);
        } catch (Exception e) {
            return root.toString();
        }
    }

    public synchronized float getSpeed() {
        return getSnapshot().speed;
    }

    public synchronized void setSpeed(float speed) {
        float clamped = Math.max(0.5f, Math.min(1.5f, speed));
        try {
            root.put("speed", clamped);
            save();
        } catch (Exception ignored) {}
    }

    public synchronized boolean splitLongSentences() {
        return getSnapshot().splitLongSentences;
    }

    public synchronized boolean removeEmoji() {
        return getSnapshot().removeEmoji;
    }

    public synchronized boolean ttsFriendlyMode() {
        return getSnapshot().ttsFriendlyMode;
    }

    /** True si ce mot ne doit pas devenir une règle de prononciation. */
    public static boolean isBlockedDictionaryKey(String word) {
        if (word == null) return true;
        String k = word.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) return true;
        return BLOCKED_DICTIONARY_KEYS.contains(k);
    }

    /**
     * @return true si la règle a bien été persistée (false = clé bloquée / champs vides / erreur).
     */
    public synchronized boolean putDictionary(String word, String pronunciation) {
        if (word == null || word.trim().isEmpty()) return false;
        if (pronunciation == null || pronunciation.trim().isEmpty()) return false;
        // Phrase multi-mots → remplacements (évite le dictionnaire phonétique mot à mot).
        if (word.trim().contains(" ")) {
            return putReplace(word, pronunciation);
        }
        if (isBlockedDictionaryKey(word)) return false;
        try {
            ensureStructure();
            JSONObject dict = root.optJSONObject("dictionary");
            if (dict == null) return false;
            dict.put(word.trim(), pronunciation.trim());
            save();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** @return true si la règle a bien été persistée. */
    public synchronized boolean putReplace(String word, String replacement) {
        if (word == null || word.trim().isEmpty()) return false;
        if (replacement == null || replacement.trim().isEmpty()) return false;
        try {
            ensureStructure();
            JSONObject replace = root.optJSONObject("replace");
            if (replace == null) return false;
            replace.put(word.trim(), replacement.trim());
            save();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** @return true si la règle a bien été persistée. */
    public synchronized boolean putExpand(String acronym, String spoken) {
        if (acronym == null || acronym.trim().isEmpty()) return false;
        if (spoken == null || spoken.trim().isEmpty()) return false;
        try {
            ensureStructure();
            JSONObject expand = root.optJSONObject("expand");
            if (expand == null) return false;
            expand.put(acronym.trim(), spoken.trim());
            save();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static final class RuleEntry {
        public final String word;
        public final String value;

        public RuleEntry(String word, String value) {
            this.word = word;
            this.value = value;
        }
    }

    public synchronized List<RuleEntry> listDictionary() {
        return listSection("dictionary");
    }

    public synchronized List<RuleEntry> listReplace() {
        return listSection("replace");
    }

    public synchronized List<RuleEntry> listExpand() {
        return listSection("expand");
    }

    public synchronized void removeDictionary(String word) {
        removeFromSection("dictionary", word);
    }

    public synchronized void removeReplace(String word) {
        removeFromSection("replace", word);
    }

    public synchronized void removeExpand(String word) {
        removeFromSection("expand", word);
    }

    private List<RuleEntry> listSection(String section) {
        List<RuleEntry> out = new ArrayList<>();
        JSONObject obj = root.optJSONObject(section);
        if (obj == null) return out;
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String value = obj.optString(key, "").trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                out.add(new RuleEntry(key, value));
            }
        }
        out.sort((a, b) -> a.word.compareToIgnoreCase(b.word));
        return out;
    }

    private void removeFromSection(String section, String word) {
        if (word == null || word.trim().isEmpty()) return;
        try {
            JSONObject obj = root.optJSONObject(section);
            if (obj != null && obj.has(word.trim())) {
                obj.remove(word.trim());
                save();
            }
        } catch (Exception ignored) {}
    }

    public synchronized String applyDictionary(String text) {
        return getSnapshot().applyDictionary(text);
    }

    public synchronized String applyReplace(String text) {
        return getSnapshot().applyReplace(text);
    }

    public synchronized String applyExpand(String text) {
        return getSnapshot().applyExpand(text);
    }

    private void rebuildCache() {
        cache = SpeechRulesSnapshot.from(root);
    }

    private void reloadFromDisk() {
        if (!rulesFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(rulesFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            root = new JSONObject(sb.toString());
            ensureStructure();
        } catch (Exception ignored) {}
    }

    private void loadOrSeed() {
        if (rulesFile.exists()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(rulesFile), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                root = new JSONObject(sb.toString());
                ensureStructure();
                migrateSpeedFromPrefs();
                boolean changed = mergeMissingFromAssets();
                changed |= upgradeKnownReplaceValues();
                changed |= purgeBlockedDictionaryKeys();
                if (changed) save();
                return;
            } catch (Exception ignored) {}
        }
        root = loadDefaultFromAssets();
        if (root == null) root = defaultRules();
        migrateSpeedFromPrefs();
        save();
    }

    /**
     * Ajoute les entrées assets absentes (nouvelles prononciations) sans écraser
     * les corrections utilisateur déjà présentes.
     */
    /**
     * Met à jour des remplacements seed dont la valeur a évolué (sans écraser
     * une correction utilisateur différente de l'ancienne valeur seed).
     */
    private boolean upgradeKnownReplaceValues() {
        JSONObject replace = root.optJSONObject("replace");
        if (replace == null) return false;
        boolean changed = false;
        changed |= upgradeReplaceIfLegacy(replace, "dis moi", "di moi", "di mwa");
        changed |= upgradeReplaceIfLegacy(replace, "dis-moi", "di moi", "di-mwa");
        changed |= upgradeReplaceIfLegacy(replace, "Dis-moi", "di moi", "di-mwa");
        return changed;
    }

    private static boolean upgradeReplaceIfLegacy(JSONObject replace, String key,
                                                  String legacyValue, String newValue) {
        if (!replace.has(key)) return false;
        String cur = replace.optString(key, "").trim();
        if (!legacyValue.equalsIgnoreCase(cur)) return false;
        try {
            replace.put(key, newValue);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean mergeMissingFromAssets() {
        JSONObject defaults = loadDefaultFromAssets();
        if (defaults == null) return false;
        boolean changed = false;
        changed |= mergeMissingSection("dictionary", defaults);
        changed |= mergeMissingSection("replace", defaults);
        changed |= mergeMissingSection("expand", defaults);
        return changed;
    }

    private boolean mergeMissingSection(String section, JSONObject defaults) {
        JSONObject src = defaults.optJSONObject(section);
        if (src == null) return false;
        JSONObject dst = root.optJSONObject(section);
        if (dst == null) {
            try {
                root.put(section, new JSONObject(src.toString()));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        boolean changed = false;
        Iterator<String> keys = src.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (dst.has(key)) continue;
            String value = src.optString(key, "").trim();
            if (value.isEmpty()) continue;
            try {
                dst.put(key, value);
                changed = true;
            } catch (Exception ignored) {}
        }
        return changed;
    }

    private JSONObject loadDefaultFromAssets() {
        try (InputStream in = appContext.getAssets().open("speech/speech_rules.json");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private void migrateSpeedFromPrefs() {
        if (appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SPEED_MIGRATED, false)) {
            return;
        }
        float legacy = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat("speech_speed", DEFAULT_SPEED);
        if (Math.abs(legacy - 0.94f) > 0.001f) {
            setSpeed(legacy);
        }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SPEED_MIGRATED, true).apply();
    }

    private void ensureStructure() {
        try {
            if (!root.has("speed")) root.put("speed", DEFAULT_SPEED);
            if (!root.has("splitLongSentences")) root.put("splitLongSentences", true);
            if (!root.has("removeEmoji")) root.put("removeEmoji", true);
            if (!root.has("ttsFriendlyMode")) root.put("ttsFriendlyMode", true);
            if (!root.has("dictionary")) root.put("dictionary", new JSONObject());
            if (!root.has("replace")) root.put("replace", new JSONObject());
            if (!root.has("expand")) root.put("expand", new JSONObject());
        } catch (Exception ignored) {}
    }

    /** Retire les fausses règles type « Dis » / « Moi » déjà persistées. */
    private boolean purgeBlockedDictionaryKeys() {
        JSONObject dict = root.optJSONObject("dictionary");
        if (dict == null) return false;
        List<String> toRemove = new ArrayList<>();
        Iterator<String> keys = dict.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (isBlockedDictionaryKey(key)) toRemove.add(key);
        }
        if (toRemove.isEmpty()) return false;
        for (String key : toRemove) dict.remove(key);
        return true;
    }

    private static JSONObject defaultRules() {
        try {
            JSONObject rules = new JSONObject();
            rules.put("speed", DEFAULT_SPEED);
            rules.put("splitLongSentences", true);
            rules.put("removeEmoji", true);
            rules.put("ttsFriendlyMode", true);
            rules.put("dictionary", new JSONObject());

            JSONObject replace = new JSONObject();
            replace.put("apps", "applis");
            replace.put("app", "appli");
            replace.put("status", "statut");
            replace.put("ok", "o k");
            replace.put("wifi", "wai faï");
            replace.put("github", "guite hub");
            replace.put("android", "androïde");
            replace.put("pegase", "pégase");
            replace.put("dis moi", "di mwa");
            replace.put("dis-moi", "di-mwa");
            replace.put("Dis-moi", "di-mwa");
            rules.put("replace", replace);

            JSONObject expand = new JSONObject();
            expand.put("SMS", "ess em esse");
            expand.put("API", "a p i");
            expand.put("OK", "o k");
            rules.put("expand", expand);

            return rules;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void save() {
        ensureStructure();
        try (FileOutputStream out = new FileOutputStream(rulesFile)) {
            out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        rebuildCache();
    }
}
