package com.pegasuscorp.orbe.orion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Détecte plusieurs fichiers dans une sortie Orion :
 * {@code === path ===} ou fences markdown (délègue à {@link GeneratedFiles}).
 */
public final class OrionFileParser {

    private static final Pattern FILE_HEADER = Pattern.compile(
            "^===\\s*([\\w./\\\\-]+)\\s*===\\s*$",
            Pattern.MULTILINE);

    public static final class ParsedFile {
        public final String path;
        public final String content;

        public ParsedFile(String path, String content) {
            this.path = path != null ? path : "output.txt";
            this.content = content != null ? content : "";
        }
    }

    private OrionFileParser() {}

    public static List<ParsedFile> parse(String orionOutput) {
        List<ParsedFile> files = new ArrayList<>();
        if (orionOutput == null || orionOutput.trim().isEmpty()) return files;

        Matcher m = FILE_HEADER.matcher(orionOutput);
        int lastEnd = 0;
        String lastName = null;
        while (m.find()) {
            if (lastName != null) {
                String body = orionOutput.substring(lastEnd, m.start()).trim();
                if (!body.isEmpty()) files.add(new ParsedFile(basename(lastName), body));
            }
            lastName = m.group(1);
            lastEnd = m.end();
            if (lastEnd < orionOutput.length() && orionOutput.charAt(lastEnd) == '\n') {
                lastEnd++;
            }
        }
        if (lastName != null) {
            String body = orionOutput.substring(lastEnd).trim();
            if (!body.isEmpty()) files.add(new ParsedFile(basename(lastName), body));
        }

        if (!files.isEmpty()) return dedupe(files);

        // Fallback : fences markdown
        List<GeneratedFiles.Artifact> arts = GeneratedFiles.extractArtifacts(orionOutput);
        for (GeneratedFiles.Artifact a : arts) {
            if (a == null) continue;
            String name = a.filename;
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith("orion_full_")) {
                continue;
            }
            files.add(new ParsedFile(a.filename, a.content));
        }
        if (files.isEmpty() && !orionOutput.trim().isEmpty()) {
            files.add(new ParsedFile(GeneratedFiles.defaultOrionName(orionOutput),
                    orionOutput.trim()));
        }
        return dedupe(files);
    }

    private static String basename(String path) {
        String p = path.replace('\\', '/');
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private static List<ParsedFile> dedupe(List<ParsedFile> in) {
        List<ParsedFile> out = new ArrayList<>();
        for (ParsedFile p : in) {
            boolean found = false;
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).path.equalsIgnoreCase(p.path)) {
                    out.set(i, p);
                    found = true;
                    break;
                }
            }
            if (!found) out.add(p);
        }
        return out.isEmpty() ? Collections.emptyList() : out;
    }
}
