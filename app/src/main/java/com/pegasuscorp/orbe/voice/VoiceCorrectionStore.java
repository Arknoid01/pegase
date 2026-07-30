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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Corrections vocales apprises localement (heard → meant) et détection des reformulations.
 */
public final class VoiceCorrectionStore {

    public static final class CorrectionEntry {
        public final String heard;
        public final String meant;
        public final String intentHint;

        CorrectionEntry(String heard, String meant, String intentHint) {
            this.heard = heard;
            this.meant = meant;
            this.intentHint = intentHint;
        }
    }

    private static final int MAX_ENTRIES = 60;

    private static final Pattern USER_CORRECTION = Pattern.compile(
            "(?i)(?:non,?\\s+)?(?:je voulais dire|je voulais|c'est pas ca,? je voulais|c'est pas ça,? je voulais|"
                    + "je parlais de|pas .+ mais|en fait,? c'est|en fait c'est)\\s+(.+)");
    private static final Pattern MEANT_INSTEAD = Pattern.compile(
            "(?i)(?:pas .+[,\\s]+)?(?:plutot|plutôt)\\s+(.+)");

    private static VoiceCorrectionStore instance;

    private final File storeFile;
    private final List<CorrectionEntry> entries = new ArrayList<>();

    private VoiceCorrectionStore(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "voice");
        if (!dir.exists()) dir.mkdirs();
        storeFile = new File(dir, "corrections.json");
        load();
        seedDefaults();
    }

    public static synchronized VoiceCorrectionStore getInstance(Context context) {
        if (instance == null) {
            instance = new VoiceCorrectionStore(context.getApplicationContext());
        }
        return instance;
    }

    public String apply(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (CorrectionEntry e : entries) {
            if (SpeechInputNormalizer.fold(out).contains(SpeechInputNormalizer.fold(e.heard))) {
                out = out.replaceAll("(?i)" + Pattern.quote(e.heard), Matcher.quoteReplacement(e.meant));
            }
        }
        return out;
    }

    public void learn(String heard, String meant, String intentHint) {
        if (heard == null || meant == null) return;
        String h = heard.trim();
        String m = meant.trim();
        if (h.isEmpty() || m.isEmpty() || h.equalsIgnoreCase(m)) return;

        entries.removeIf(e -> e.heard.equalsIgnoreCase(h));
        entries.add(0, new CorrectionEntry(h, m, intentHint != null ? intentHint : ""));
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
        save();
    }

    /** Extrait une reformulation explicite (« non, je voulais dire… »). */
    public static String extractCorrectionPhrase(String transcript) {
        if (transcript == null) return null;
        Matcher m = USER_CORRECTION.matcher(transcript.trim());
        if (m.find()) return cleanTail(m.group(1));
        m = MEANT_INSTEAD.matcher(transcript.trim());
        if (m.find()) return cleanTail(m.group(1));
        return null;
    }

    public List<CorrectionEntry> getEntries() {
        return new ArrayList<>(entries);
    }

    public void updateAt(int index, String heard, String meant, String intentHint) {
        if (index < 0 || index >= entries.size()) return;
        String h = heard == null ? "" : heard.trim();
        String m = meant == null ? "" : meant.trim();
        if (h.isEmpty() || m.isEmpty()) return;
        String intent = intentHint == null ? "" : intentHint.trim();
        entries.remove(index);
        entries.removeIf(e -> e.heard.equalsIgnoreCase(h));
        entries.add(Math.min(index, entries.size()), new CorrectionEntry(h, m, intent));
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
        save();
    }

    public void removeAt(int index) {
        if (index < 0 || index >= entries.size()) return;
        entries.remove(index);
        save();
    }

    public void clearAll() {
        if (entries.isEmpty()) return;
        entries.clear();
        save();
    }

    public void resetToDefaults() {
        entries.clear();
        addDefaultEntries();
        save();
    }

    private void seedDefaults() {
        if (!entries.isEmpty()) return;
        addDefaultEntries();
        save();
    }

    private void addDefaultEntries() {
        putEntry("psg talon", "PSG foot", "sports");
        putEntry("mettreo", "météo demain", "météo");
        putEntry("pegasse", "pégase", "");
        putEntry("foot ball", "foot", "sports");
        putEntry("draft punk", "Daft Punk", "spotify");
        putEntry("daft punck", "Daft Punk", "spotify");
        putEntry("stromait", "Stromae", "spotify");
        putEntry("cold play", "Coldplay", "spotify");
        putEntry("week end", "The Weeknd", "spotify");
    }

    private void putEntry(String heard, String meant, String intent) {
        entries.removeIf(e -> e.heard.equalsIgnoreCase(heard));
        entries.add(new CorrectionEntry(heard, meant, intent));
    }

    private void load() {
        entries.clear();
        if (!storeFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(storeFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONObject(sb.toString()).optJSONArray("entries");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                CorrectionEntry e = new CorrectionEntry(
                        o.optString("heard", ""),
                        o.optString("meant", ""),
                        o.optString("intent", ""));
                if (!e.heard.isEmpty() && !e.meant.isEmpty()) {
                    entries.add(e);
                }
            }
        } catch (Exception ignored) {}
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (CorrectionEntry e : entries) {
                arr.put(new JSONObject()
                        .put("heard", e.heard)
                        .put("meant", e.meant)
                        .put("intent", e.intentHint));
            }
            JSONObject root = new JSONObject().put("entries", arr);
            try (FileOutputStream out = new FileOutputStream(storeFile)) {
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private static String cleanTail(String text) {
        if (text == null) return "";
        return text.trim().replaceAll("[?.!]+$", "").trim();
    }
}
