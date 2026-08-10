package com.pegasuscorp.orbe.pcshell;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.fs.PegaseFileSystem;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Historique des exécutions — {@code files/pc-shell/executions.jsonl} (50 dernières).
 */
public final class PcShellExecutionLog {

    public static final int MAX_ENTRIES = 50;
    /** Aperçu historique (fin de sortie). */
    public static final int OUTPUT_PREVIEW_CHARS = 4096;
    /** Limite affichage / capture SSH (voir {@link PcShellPrefs#MAX_OUTPUT_CHARS}). */
    public static final int TERMINAL_DISPLAY_MAX_CHARS = PcShellPrefs.MAX_OUTPUT_CHARS;

    public static final class Entry {
        public final long atMs;
        public final String commandId;
        public final String label;
        public final String command;
        public final int exitCode;
        public final String outputPreview;
        /** Sortie complète stdout+stderr (non tronquée). */
        public final String outputFull;
        public final String error;
        public final boolean cancelled;

        public Entry(long atMs, String commandId, String label, String command,
                int exitCode, String outputPreview, String outputFull, String error,
                boolean cancelled) {
            this.atMs = atMs;
            this.commandId = commandId != null ? commandId : "";
            this.label = label != null ? label : "";
            this.command = command != null ? command : "";
            this.exitCode = exitCode;
            this.outputPreview = outputPreview != null ? outputPreview : "";
            this.outputFull = outputFull != null ? outputFull : "";
            this.error = error != null ? error : "";
            this.cancelled = cancelled;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("atMs", atMs);
            o.put("commandId", commandId);
            o.put("label", label);
            o.put("command", command);
            o.put("exitCode", exitCode);
            o.put("outputPreview", outputPreview);
            o.put("outputFull", outputFull);
            o.put("error", error);
            o.put("cancelled", cancelled);
            return o;
        }

        static Entry fromJson(JSONObject o) {
            if (o == null) return null;
            String preview = o.optString("outputPreview", "");
            String full = o.optString("outputFull", "");
            if (full.isEmpty()) {
                full = preview;
            }
            return new Entry(
                    o.optLong("atMs", 0L),
                    o.optString("commandId", ""),
                    o.optString("label", ""),
                    o.optString("command", ""),
                    o.optInt("exitCode", -1),
                    preview,
                    full,
                    o.optString("error", ""),
                    o.optBoolean("cancelled", false));
        }

        /** Texte à afficher dans l'écran « Voir tout ». */
        public String displayFullOutput() {
            if (!error.isEmpty()) return error;
            return outputFull;
        }

        public boolean hasExpandableOutput() {
            if (!error.isEmpty()) return true;
            if (outputFull.isEmpty()) return false;
            return outputFull.length() > outputPreview.length()
                    || !outputFull.equals(outputPreview);
        }
    }

    private PcShellExecutionLog() {}

    public static synchronized void append(Context context, Entry entry) {
        if (context == null || entry == null) return;
        File file = PegaseFileSystem.get(context).pcShellExecutionsJsonl();
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            String line = entry.toJson().toString() + "\n";
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
            trimToMax(file, MAX_ENTRIES);
        } catch (Exception ignored) {
        }
    }

    public static synchronized List<Entry> listRecent(Context context) {
        if (context == null) return Collections.emptyList();
        File file = PegaseFileSystem.get(context).pcShellExecutionsJsonl();
        if (!file.isFile()) return Collections.emptyList();
        List<Entry> all = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    Entry e = Entry.fromJson(new JSONObject(line));
                    if (e != null) all.add(e);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        Collections.reverse(all);
        if (all.size() > MAX_ENTRIES) {
            return all.subList(0, MAX_ENTRIES);
        }
        return all;
    }

    public static String combineOutput(String stdout, String stderr) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(stdout)) {
            sb.append(stdout);
        }
        if (!TextUtils.isEmpty(stderr)) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
            if (sb.length() > 0) {
                sb.append("--- stderr ---\n");
            }
            sb.append(stderr);
        }
        return sb.toString();
    }

    /** Derniers {@code maxChars} caractères — aperçu mobile compact. */
    public static String tailPreview(String text, int maxChars) {
        if (text == null || text.isEmpty()) return "";
        if (text.length() <= maxChars) return text;
        int hidden = text.length() - maxChars;
        return "… [" + hidden + " car. au début masqués]\n" + text.substring(text.length() - maxChars);
    }

    public static String preview(String stdout, String stderr) {
        return tailPreview(combineOutput(stdout, stderr), OUTPUT_PREVIEW_CHARS);
    }

    public static boolean isOutputTruncatedAtCapture(String combined) {
        return combined != null && combined.contains("[sortie tronquée");
    }

    private static void trimToMax(File file, int max) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) lines.add(line);
            }
        }
        if (lines.size() <= max) return;
        List<String> tail = lines.subList(lines.size() - max, lines.size());
        StringBuilder sb = new StringBuilder();
        for (String l : tail) sb.append(l).append('\n');
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
