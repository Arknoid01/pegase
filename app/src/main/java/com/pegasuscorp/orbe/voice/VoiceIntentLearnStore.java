package com.pegasuscorp.orbe.voice;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Corpus v2 : groupes d'intentions avec synonymes, séquences composites, slots.
 * Seules confirmations et corrections explicites alimentent le store.
 */
public final class VoiceIntentLearnStore {

    public static final class UtteranceVariant {
        public final String text;
        public final String fold;
        public final int confirmations;

        UtteranceVariant(String text, String fold, int confirmations) {
            this.text = text;
            this.fold = fold;
            this.confirmations = confirmations;
        }
    }

    public static final class IntentGroup {
        public final String groupId;
        public final String label;
        public final String toolJson;
        public final String intentHint;
        public final List<UtteranceVariant> variants;
        public final long learnedAtMs;
        public final long lastUsedAtMs;
        public final String source;

        IntentGroup(String groupId, String label, String toolJson, String intentHint,
                List<UtteranceVariant> variants, long learnedAtMs, long lastUsedAtMs,
                String source) {
            this.groupId = groupId;
            this.label = label;
            this.toolJson = toolJson;
            this.intentHint = intentHint;
            this.variants = variants;
            this.learnedAtMs = learnedAtMs;
            this.lastUsedAtMs = lastUsedAtMs;
            this.source = source;
        }

        public int totalConfirmations() {
            int sum = 0;
            for (UtteranceVariant v : variants) sum += v.confirmations;
            return sum;
        }

        public String primaryUtterance() {
            return variants.isEmpty() ? "" : variants.get(0).text;
        }
    }

    /** Vue aplatie pour l'UI (une carte = un groupe). */
    public static final class LearnedIntent {
        public final String utterance;
        public final String utteranceFold;
        public final String toolJson;
        public final String intentHint;
        public final String label;
        public final List<String> synonyms;
        public final double confidence;
        public final int confirmations;
        public final long learnedAtMs;
        public final long lastUsedAtMs;
        public final String source;
        public final boolean composite;

        LearnedIntent(IntentGroup group) {
            this.utterance = group.primaryUtterance();
            this.utteranceFold = variantsFold(group);
            this.toolJson = group.toolJson;
            this.intentHint = group.intentHint;
            this.label = group.label;
            this.synonyms = synonymTexts(group);
            this.confidence = 1.0;
            this.confirmations = group.totalConfirmations();
            this.learnedAtMs = group.learnedAtMs;
            this.lastUsedAtMs = group.lastUsedAtMs;
            this.source = group.source;
            this.composite = LearnedToolPayload.isComposite(group.toolJson);
        }
    }

    public static final class LearnMatch {
        public final IntentGroup group;
        public final UtteranceVariant variant;
        public final double score;

        LearnMatch(IntentGroup group, UtteranceVariant variant, double score) {
            this.group = group;
            this.variant = variant;
            this.score = score;
        }

        /** Compat v1 */
        public LearnedIntent entry() {
            return new LearnedIntent(group);
        }
    }

    private static final int MAX_GROUPS = 200;
    private static final int MAX_VARIANTS_PER_GROUP = 24;
    private static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;
    private static final double MATCH_THRESHOLD = 0.72;
    private static final double DISAMBIG_GAP = 0.12;

    private static VoiceIntentLearnStore instance;

    private final File storeFile;
    private final List<IntentGroup> groups = new ArrayList<>();

    private VoiceIntentLearnStore(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "voice");
        if (!dir.exists()) dir.mkdirs();
        storeFile = new File(dir, "intent_corpus.json");
        load();
    }

    public static synchronized VoiceIntentLearnStore getInstance(Context context) {
        if (instance == null) {
            instance = new VoiceIntentLearnStore(context.getApplicationContext());
        }
        return instance;
    }

    public void recordConfirmation(String utterance, String toolJson, String intentHint) {
        upsertVariant(utterance, toolJson, intentHint, "confirmation");
    }

    public void recordCorrection(String rejectedUtterance, String correctedUtterance,
            String toolJson, String intentHint) {
        if (rejectedUtterance != null && !rejectedUtterance.trim().isEmpty()) {
            removeVariantByFold(SpeechInputNormalizer.fold(rejectedUtterance.trim()));
        }
        upsertVariant(correctedUtterance, toolJson, intentHint, "correction");
    }

    public void recordDisambiguationChoice(String utterance, String toolJson, String intentHint) {
        upsertVariant(utterance, toolJson, intentHint, "disambiguation");
    }

    public LearnMatch match(String transcript) {
        List<LearnMatch> candidates = matchCandidates(transcript);
        if (candidates.isEmpty()) return null;
        LearnMatch best = candidates.get(0);
        touch(best.group, best.variant.fold);
        return best;
    }

    public List<LearnMatch> matchCandidates(String transcript) {
        if (transcript == null || transcript.trim().isEmpty() || groups.isEmpty()) {
            return Collections.emptyList();
        }
        String fold = SpeechInputNormalizer.fold(transcript.trim());
        List<LearnMatch> out = new ArrayList<>();
        for (IntentGroup group : groups) {
            for (UtteranceVariant variant : group.variants) {
                double score = scoreMatch(fold, variant.fold);
                if (score >= MATCH_THRESHOLD) {
                    out.add(new LearnMatch(group, variant, score));
                }
            }
        }
        out.sort((a, b) -> Double.compare(b.score, a.score));
        return out;
    }

    public boolean needsDisambiguation(List<LearnMatch> candidates) {
        if (candidates == null || candidates.size() < 2) return false;
        LearnMatch first = candidates.get(0);
        LearnMatch second = candidates.get(1);
        if (first.score < MATCH_THRESHOLD || second.score < MATCH_THRESHOLD) return false;
        if (Math.abs(first.score - second.score) > DISAMBIG_GAP) return false;
        return !normalizeToolKey(first.group.toolJson).equals(
                normalizeToolKey(second.group.toolJson));
    }

    public List<LearnedIntent> getEntries() {
        List<LearnedIntent> out = new ArrayList<>();
        for (IntentGroup g : groups) out.add(new LearnedIntent(g));
        return out;
    }

    public int countLearnedThisWeek() {
        long since = System.currentTimeMillis() - WEEK_MS;
        int count = 0;
        for (IntentGroup g : groups) {
            if (g.learnedAtMs >= since) count += g.variants.size();
        }
        return count;
    }

    public void removeAt(int index) {
        if (index < 0 || index >= groups.size()) return;
        groups.remove(index);
        save();
    }

    public void removeByUtterance(String utterance) {
        removeVariantByFold(SpeechInputNormalizer.fold(utterance == null ? "" : utterance.trim()));
    }

    public void clearAll() {
        if (groups.isEmpty()) return;
        groups.clear();
        save();
    }

    public String exportJson() {
        try {
            return buildRootJson().toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void upsertVariant(String utterance, String toolJson, String intentHint, String source) {
        if (utterance == null || toolJson == null) return;
        String u = utterance.trim();
        String tj = toolJson.trim();
        if (u.isEmpty() || tj.isEmpty()) return;

        String fold = SpeechInputNormalizer.fold(u);
        String hint = intentHint == null ? "" : intentHint.trim();
        String label = LearnedToolPayload.label(tj);
        long now = System.currentTimeMillis();
        String toolKey = normalizeToolKey(tj);

        removeVariantByFold(fold);

        IntentGroup existing = findGroupByToolKey(toolKey);
        if (existing != null) {
            List<UtteranceVariant> variants = new ArrayList<>(existing.variants);
            boolean found = false;
            for (int i = 0; i < variants.size(); i++) {
                UtteranceVariant v = variants.get(i);
                if (v.fold.equals(fold)) {
                    variants.set(i, new UtteranceVariant(u, fold, v.confirmations + 1));
                    found = true;
                    break;
                }
            }
            if (!found) {
                variants.add(0, new UtteranceVariant(u, fold, 1));
                while (variants.size() > MAX_VARIANTS_PER_GROUP) {
                    variants.remove(variants.size() - 1);
                }
            }
            replaceGroup(existing.groupId, new IntentGroup(
                    existing.groupId, label, tj, hint, variants,
                    existing.learnedAtMs, now, source));
            return;
        }

        String groupId = "ig_" + Integer.toHexString(toolKey.hashCode()) + "_" + now;
        List<UtteranceVariant> variants = new ArrayList<>();
        variants.add(new UtteranceVariant(u, fold, 1));
        groups.add(0, new IntentGroup(groupId, label, tj, hint, variants, now, now, source));
        while (groups.size() > MAX_GROUPS) groups.remove(groups.size() - 1);
        save();
    }

    private void removeVariantByFold(String fold) {
        if (fold == null || fold.isEmpty()) return;
        boolean changed = false;
        for (int i = groups.size() - 1; i >= 0; i--) {
            IntentGroup g = groups.get(i);
            List<UtteranceVariant> variants = new ArrayList<>(g.variants);
            variants.removeIf(v -> v.fold.equals(fold));
            if (variants.isEmpty()) {
                groups.remove(i);
                changed = true;
            } else if (variants.size() != g.variants.size()) {
                groups.set(i, new IntentGroup(
                        g.groupId, g.label, g.toolJson, g.intentHint, variants,
                        g.learnedAtMs, g.lastUsedAtMs, g.source));
                changed = true;
            }
        }
        if (changed) save();
    }

    private void touch(IntentGroup group, String variantFold) {
        long now = System.currentTimeMillis();
        List<UtteranceVariant> variants = new ArrayList<>();
        for (UtteranceVariant v : group.variants) {
            if (v.fold.equals(variantFold)) {
                variants.add(new UtteranceVariant(v.text, v.fold, v.confirmations + 1));
            } else {
                variants.add(v);
            }
        }
        replaceGroup(group.groupId, new IntentGroup(
                group.groupId, group.label, group.toolJson, group.intentHint, variants,
                group.learnedAtMs, now, group.source));
    }

    private IntentGroup findGroupByToolKey(String toolKey) {
        for (IntentGroup g : groups) {
            if (normalizeToolKey(g.toolJson).equals(toolKey)) return g;
        }
        return null;
    }

    private void replaceGroup(String groupId, IntentGroup updated) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).groupId.equals(groupId)) {
                groups.set(i, updated);
                save();
                return;
            }
        }
    }

    private static String normalizeToolKey(String toolJson) {
        try {
            return new JSONObject(toolJson.trim()).toString();
        } catch (Exception e) {
            return toolJson == null ? "" : toolJson.trim();
        }
    }

    private static double scoreMatch(String transcriptFold, String learnedFold) {
        if (transcriptFold.isEmpty() || learnedFold.isEmpty()) return 0;
        if (transcriptFold.equals(learnedFold)) return 0.98;
        if (containsWholePhrase(transcriptFold, learnedFold)) return 0.9;
        if (containsWholePhrase(learnedFold, transcriptFold)) return 0.82;
        return 0;
    }

    private static boolean containsWholePhrase(String haystack, String phrase) {
        if (phrase.isEmpty()) return false;
        if (haystack.equals(phrase)) return true;
        return haystack.startsWith(phrase + " ")
                || haystack.endsWith(" " + phrase)
                || haystack.contains(" " + phrase + " ");
    }

    private static List<String> synonymTexts(IntentGroup group) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i < group.variants.size(); i++) {
            out.add(group.variants.get(i).text);
        }
        return out;
    }

    private static String variantsFold(IntentGroup group) {
        return group.variants.isEmpty() ? "" : group.variants.get(0).fold;
    }

    private void load() {
        groups.clear();
        if (!storeFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(storeFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("groups");
            if (arr != null) {
                loadGroups(arr);
                return;
            }
            JSONArray legacy = root.optJSONArray("entries");
            if (legacy != null) migrateLegacy(legacy);
        } catch (Exception ignored) {}
    }

    private void loadGroups(JSONArray arr) throws Exception {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            JSONArray varArr = o.optJSONArray("variants");
            if (varArr == null || varArr.length() == 0) continue;
            List<UtteranceVariant> variants = new ArrayList<>();
            for (int j = 0; j < varArr.length(); j++) {
                JSONObject v = varArr.getJSONObject(j);
                String text = v.optString("text", "");
                if (text.isEmpty()) continue;
                variants.add(new UtteranceVariant(
                        text,
                        v.optString("fold", SpeechInputNormalizer.fold(text)),
                        v.optInt("confirmations", 1)));
            }
            if (variants.isEmpty()) continue;
            groups.add(new IntentGroup(
                    o.optString("group_id", "ig_" + i),
                    o.optString("label", LearnedToolPayload.label(o.optString("tool_json", ""))),
                    o.optString("tool_json", ""),
                    o.optString("intent_hint", ""),
                    variants,
                    o.optLong("learned_at_ms", 0L),
                    o.optLong("last_used_at_ms", 0L),
                    o.optString("source", "confirmation")));
        }
    }

    private void migrateLegacy(JSONArray legacy) {
        for (int i = 0; i < legacy.length(); i++) {
            JSONObject o = legacy.optJSONObject(i);
            if (o == null) continue;
            String utterance = o.optString("utterance", "");
            String toolJson = o.optString("tool_json", "");
            if (utterance.isEmpty() || toolJson.isEmpty()) continue;
            upsertVariant(utterance, toolJson, o.optString("intent_hint", ""), "migration");
        }
    }

    private JSONObject buildRootJson() throws Exception {
        JSONArray arr = new JSONArray();
        for (IntentGroup g : groups) {
            JSONArray variants = new JSONArray();
            for (UtteranceVariant v : g.variants) {
                variants.put(new JSONObject()
                        .put("text", v.text)
                        .put("fold", v.fold)
                        .put("confirmations", v.confirmations));
            }
            arr.put(new JSONObject()
                    .put("group_id", g.groupId)
                    .put("label", g.label)
                    .put("tool_json", g.toolJson)
                    .put("intent_hint", g.intentHint)
                    .put("variants", variants)
                    .put("learned_at_ms", g.learnedAtMs)
                    .put("last_used_at_ms", g.lastUsedAtMs)
                    .put("source", g.source));
        }
        return new JSONObject()
                .put("version", 2)
                .put("groups", arr);
    }

    private void save() {
        try {
            try (FileOutputStream out = new FileOutputStream(storeFile)) {
                out.write(buildRootJson().toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }
}
