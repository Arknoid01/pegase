package com.pegasuscorp.orbe.voice;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Règles vocales compilées en RAM : patterns pré-calculés pour un accès rapide à chaque phrase.
 */
public final class SpeechRulesSnapshot {

    private static final class WordRule {
        final Pattern pattern;
        final String replacement;

        WordRule(Pattern pattern, String replacement) {
            this.pattern = pattern;
            this.replacement = replacement;
        }
    }

    public final float speed;
    public final boolean splitLongSentences;
    public final boolean removeEmoji;
    public final boolean ttsFriendlyMode;

    private final WordRule[] dictionary;
    private final WordRule[] replace;
    private final WordRule[] expand;

    private SpeechRulesSnapshot(float speed, boolean splitLongSentences, boolean removeEmoji,
                                boolean ttsFriendlyMode,
                                WordRule[] dictionary, WordRule[] replace, WordRule[] expand) {
        this.speed = speed;
        this.splitLongSentences = splitLongSentences;
        this.removeEmoji = removeEmoji;
        this.ttsFriendlyMode = ttsFriendlyMode;
        this.dictionary = dictionary;
        this.replace = replace;
        this.expand = expand;
    }

    static SpeechRulesSnapshot from(JSONObject root) {
        float speed = (float) root.optDouble("speed", 0.87);
        boolean splitLong = root.optBoolean("splitLongSentences", true);
        boolean removeEmoji = root.optBoolean("removeEmoji", true);
        boolean ttsFriendly = root.optBoolean("ttsFriendlyMode", true);
        return new SpeechRulesSnapshot(
                speed,
                splitLong,
                removeEmoji,
                ttsFriendly,
                // Dictionnaire : insensible à la casse sauf clés courtes (acronymes).
                compileMap(root.optJSONObject("dictionary"), Section.DICTIONARY),
                compileMap(root.optJSONObject("replace"), Section.REPLACE),
                compileMap(root.optJSONObject("expand"), Section.EXPAND));
    }

    private enum Section { DICTIONARY, REPLACE, EXPAND }

    // Pas de UNICODE_CHARACTER_CLASS : non supporté sur Android (crash au boot).
    private static final int WORD_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /**
     * Frontières « mot » Unicode via lookarounds — {@code \\b} ASCII rate
     * café / C++ / C# une fois UNICODE_CHARACTER_CLASS retiré.
     */
    private static Pattern wordPattern(String key, boolean caseSensitive) {
        int flags = caseSensitive ? 0 : WORD_FLAGS;
        String body = Pattern.quote(key);
        return Pattern.compile("(?<![\\p{L}\\p{N}_])" + body + "(?![\\p{L}\\p{N}_])", flags);
    }

    private static WordRule[] compileMap(JSONObject map, Section section) {
        if (map == null) return new WordRule[0];
        List<Entry> entries = new ArrayList<>();
        Iterator<String> keys = map.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String value = map.optString(key, "");
            if (key.isEmpty() || value.isEmpty()) continue;
            // Évite « Dis » / « Moi » / « Chat »… qui casseraient le français.
            if (section == Section.DICTIONARY
                    && SpeechRulesStore.isBlockedDictionaryKey(key)) {
                continue;
            }
            entries.add(new Entry(key, value));
        }
        entries.sort((a, b) -> Integer.compare(b.key.length(), a.key.length()));

        WordRule[] rules = new WordRule[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            // Acronymes courts / TOUT-EN-MAJ : casse exacte (évite « rest » / « x »).
            // « Git » / « Qwen » restent insensibles à la casse.
            boolean caseSensitive = section == Section.DICTIONARY && isStrictCaseKey(e.key);
            rules[i] = new WordRule(wordPattern(e.key, caseSensitive), e.value);
        }
        return rules;
    }

    private static boolean isStrictCaseKey(String key) {
        if (key == null || key.isEmpty()) return true;
        if (key.length() <= 2) return true;
        String upper = key.toUpperCase(Locale.ROOT);
        return key.equals(upper);
    }

    private static final class Entry {
        final String key;
        final String value;

        Entry(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    public String applyDictionary(String text) {
        return applyRules(text, dictionary);
    }

    public String applyReplace(String text) {
        return applyRules(text, replace);
    }

    public String applyExpand(String text) {
        return applyRules(text, expand);
    }

    private static String applyRules(String text, WordRule[] rules) {
        if (text == null || text.isEmpty() || rules.length == 0) return text;
        String result = text;
        for (WordRule rule : rules) {
            result = rule.pattern.matcher(result)
                    .replaceAll(Matcher.quoteReplacement(rule.replacement));
        }
        return result;
    }

    int ruleCount() {
        return dictionary.length + replace.length + expand.length;
    }
}
