package com.pegasuscorp.orbe.diag;

import android.content.Context;

import com.pegasuscorp.orbe.contextstore.ContextSearchIndex;
import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.fs.PegaseFileSystem;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fichier {@code files/diag/corrections.md} — backlog de corrections Pégase.
 * Sections : {@code ## En attente 🔴} (cases {@code [ ]}) et {@code ## Terminé ✅} ({@code [x]}).
 */
public final class CorrectionsStore {

    public static final String FILENAME = "corrections.md";
    public static final String SECTION_PENDING = "## En attente 🔴";
    public static final String SECTION_DONE = "## Terminé ✅";

    private static final String TEMPLATE =
            "# Corrections\n\n"
                    + SECTION_PENDING + "\n\n"
                    + SECTION_DONE + "\n";

    private CorrectionsStore() {}

    public static File file(Context ctx) {
        return PegaseFileSystem.get(ctx).correctionsMd();
    }

    /** Lit le markdown (crée le fichier vide si besoin). */
    public static synchronized String read(Context ctx) {
        ensureFile(ctx);
        return readUtf8(file(ctx));
    }

    /**
     * Ajoute des problèmes sous {@link #SECTION_PENDING} sans doublon.
     * @return nombre de nouvelles lignes ajoutées
     */
    public static synchronized int mergePending(Context ctx, List<String> problems) {
        if (problems == null || problems.isEmpty()) {
            ensureFile(ctx);
            return 0;
        }
        String md = read(ctx);
        List<String> existing = allProblemKeys(md);
        List<String> toAdd = new ArrayList<>();
        for (String raw : problems) {
            String problem = cleanProblem(raw);
            if (problem.isEmpty()) continue;
            String key = foldKey(problem);
            if (key.isEmpty()) continue;
            boolean dup = false;
            for (String e : existing) {
                if (e.equals(key) || e.contains(key) || key.contains(e)) {
                    dup = true;
                    break;
                }
            }
            if (dup) continue;
            existing.add(key);
            toAdd.add(problem);
        }
        if (toAdd.isEmpty()) {
            syncContext(ctx, md);
            return 0;
        }
        String updated = insertPending(md, toAdd);
        writeUtf8(file(ctx), updated);
        syncContext(ctx, updated);
        return toAdd.size();
    }

    public static synchronized int countPending(Context ctx) {
        return listPendingItems(read(ctx)).size();
    }

    public static synchronized List<String> listPendingItems(Context ctx) {
        return listPendingItems(read(ctx));
    }

    /** Phrase orale listant les cases non cochées. */
    public static synchronized String speakPendingList(Context ctx) {
        List<String> items = listPendingItems(ctx);
        if (items.isEmpty()) {
            return "Rien en attente dans corrections.md — tout est à jour.";
        }
        StringBuilder sb = new StringBuilder();
        if (items.size() == 1) {
            sb.append("Il reste une correction : ").append(items.get(0)).append('.');
        } else {
            sb.append("Il reste ").append(items.size()).append(" corrections : ");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(i == items.size() - 1 ? ", et " : ", ");
                sb.append(items.get(i));
            }
            sb.append('.');
        }
        return sb.toString();
    }

    public static synchronized String speakPendingCount(Context ctx) {
        int n = countPending(ctx);
        if (n == 0) return "Aucune correction en attente.";
        if (n == 1) return "Une correction en attente.";
        return n + " corrections en attente.";
    }

    /**
     * Coche les items dont le texte correspond à {@code query}, déplace sous Terminé.
     * @return message oral
     */
    public static synchronized String markDone(Context ctx, String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return "Précise quelle correction marquer comme terminée.";
        }
        String md = read(ctx);
        List<String> pending = listPendingItems(md);
        if (pending.isEmpty()) {
            return "Aucune correction en attente.";
        }
        String qKey = foldKey(q);
        List<String> matched = new ArrayList<>();
        for (String item : pending) {
            String ik = foldKey(item);
            if (ik.contains(qKey) || qKey.contains(ik)
                    || item.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))) {
                matched.add(item);
            }
        }
        if (matched.isEmpty()) {
            return "Je n'ai pas trouvé « " + q + " » dans les corrections en attente.";
        }
        String updated = moveToDone(md, matched);
        writeUtf8(file(ctx), updated);
        syncContext(ctx, updated);
        if (matched.size() == 1) {
            return "C'est noté — « " + matched.get(0) + " » est passé en terminé.";
        }
        return "C'est noté — " + matched.size() + " corrections marquées comme terminées.";
    }

    // ------------------------------------------------------------------ parse

    static List<String> listPendingItems(String md) {
        List<String> out = new ArrayList<>();
        if (md == null || md.isEmpty()) return out;
        String section = sectionBody(md, SECTION_PENDING, SECTION_DONE);
        for (String line : section.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("- [ ]") || t.startsWith("-[ ]") || t.startsWith("* [ ]")) {
                String body = stripCheckbox(t);
                if (!body.isEmpty()) out.add(body);
            }
        }
        return out;
    }

    static String insertPending(String md, List<String> problems) {
        String base = (md == null || md.trim().isEmpty()) ? TEMPLATE : md;
        if (!base.contains(SECTION_PENDING)) {
            base = TEMPLATE + "\n" + base;
        }
        if (!base.contains(SECTION_DONE)) {
            base = base.trim() + "\n\n" + SECTION_DONE + "\n";
        }
        int pendingIdx = base.indexOf(SECTION_PENDING);
        int doneIdx = base.indexOf(SECTION_DONE);
        if (pendingIdx < 0) return base;
        int insertAt = pendingIdx + SECTION_PENDING.length();
        // après le titre + éventuelle ligne vide
        if (insertAt < base.length() && base.charAt(insertAt) == '\n') insertAt++;
        StringBuilder block = new StringBuilder();
        for (String p : problems) {
            block.append("- [ ] ").append(p).append('\n');
        }
        return base.substring(0, insertAt) + block + base.substring(insertAt);
    }

    static String moveToDone(String md, List<String> matched) {
        String base = md == null ? TEMPLATE : md;
        if (!base.contains(SECTION_DONE)) {
            base = base.trim() + "\n\n" + SECTION_DONE + "\n";
        }
        List<String> keys = new ArrayList<>();
        for (String m : matched) keys.add(foldKey(m));

        StringBuilder kept = new StringBuilder();
        StringBuilder doneBlock = new StringBuilder();
        boolean inPending = false;
        boolean inDone = false;
        for (String line : base.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("## ") && t.contains("En attente")) {
                inPending = true;
                inDone = false;
                kept.append(line).append('\n');
                continue;
            }
            if (t.startsWith("## ") && t.contains("Termin")) {
                inPending = false;
                inDone = true;
                kept.append(line).append('\n');
                continue;
            }
            if (t.startsWith("## ")) {
                inPending = false;
                inDone = false;
                kept.append(line).append('\n');
                continue;
            }
            if (inPending && isUnchecked(t)) {
                String body = stripCheckbox(t);
                if (matchesAny(foldKey(body), keys)) {
                    doneBlock.append("- [x] ").append(body).append('\n');
                    continue;
                }
            }
            kept.append(line).append('\n');
        }
        String result = kept.toString();
        if (doneBlock.length() == 0) return result;
        int doneIdx = result.indexOf(SECTION_DONE);
        if (doneIdx < 0) {
            return result.trim() + "\n\n" + SECTION_DONE + "\n" + doneBlock;
        }
        int insertAt = doneIdx + SECTION_DONE.length();
        if (insertAt < result.length() && result.charAt(insertAt) == '\n') insertAt++;
        return result.substring(0, insertAt) + doneBlock + result.substring(insertAt);
    }

    private static boolean matchesAny(String key, List<String> keys) {
        for (String k : keys) {
            if (key.equals(k) || key.contains(k) || k.contains(key)) return true;
        }
        return false;
    }

    private static boolean isUnchecked(String t) {
        return t.startsWith("- [ ]") || t.startsWith("-[ ]") || t.startsWith("* [ ]");
    }

    private static String stripCheckbox(String line) {
        String t = line.trim();
        if (t.startsWith("- [ ]")) t = t.substring(5);
        else if (t.startsWith("- [x]") || t.startsWith("- [X]")) t = t.substring(5);
        else if (t.startsWith("-[ ]")) t = t.substring(4);
        else if (t.startsWith("* [ ]")) t = t.substring(5);
        return t.trim();
    }

    private static String sectionBody(String md, String startHeader, String endHeader) {
        int start = md.indexOf(startHeader);
        if (start < 0) return "";
        start += startHeader.length();
        int end = md.indexOf(endHeader, start);
        if (end < 0) end = md.length();
        return md.substring(start, end);
    }

    private static List<String> allProblemKeys(String md) {
        List<String> keys = new ArrayList<>();
        if (md == null) return keys;
        for (String line : md.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("- [") || t.startsWith("*[") || t.startsWith("* [")) {
                String body = stripCheckbox(t.replaceFirst("^\\*\\s*", "- "));
                if (t.contains("[x]") || t.contains("[X]") || t.contains("[ ]")) {
                    String k = foldKey(body);
                    if (!k.isEmpty()) keys.add(k);
                }
            }
        }
        return keys;
    }

    static String cleanProblem(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        while (t.startsWith("—") || t.startsWith("-") || t.startsWith("•")) {
            t = t.substring(1).trim();
        }
        if (t.startsWith("[ ]") || t.startsWith("[x]")) t = t.substring(3).trim();
        // une ligne
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl).trim();
        if (t.length() > 220) t = t.substring(0, 217) + "…";
        return t;
    }

    static String foldKey(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a').replace('ù', 'u').replace('ô', 'o')
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return t;
    }

    private static void ensureFile(Context ctx) {
        File f = file(ctx);
        if (!f.exists()) {
            writeUtf8(f, TEMPLATE);
            syncContext(ctx, TEMPLATE);
        }
    }

    private static void syncContext(Context ctx, String content) {
        try {
            ContextualFileStore store = ContextualFileStore.getInstance(ctx);
            store.ensureCorrectionsKeyword();
            ContextSearchIndex.getInstance(ctx).indexFile(file(ctx));
        } catch (Exception ignored) {}
        // Toujours réécrire le fichier canonique (déjà fait) ; le keyword pointe dessus.
    }

    private static String readUtf8(File f) {
        if (f == null || !f.isFile()) return TEMPLATE;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        } catch (Exception e) {
            return TEMPLATE;
        }
        return sb.length() == 0 ? TEMPLATE : sb.toString();
    }

    private static void writeUtf8(File f, String content) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }
}
