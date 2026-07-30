package com.pegasuscorp.orbe.orion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Session de fichiers générés par Orion (review → valider → commit groupé).
 * Une entrée par nom de fichier (casse ignorée) — la dernière version gagne.
 */
public final class OrionFileSession {

    public enum FileStatus {
        PENDING,
        VALIDATED,
        REJECTED
    }

    public static final class OrionFile {
        public final String path;
        public String content;
        public FileStatus status;
        public final long generatedAt;

        public OrionFile(String path, String content, FileStatus status) {
            this.path = path == null ? "output.txt" : path;
            this.content = content == null ? "" : content;
            this.status = status == null ? FileStatus.PENDING : status;
            this.generatedAt = System.currentTimeMillis();
        }

        public int lineCount() {
            if (content == null || content.isEmpty()) return 0;
            int n = 1;
            for (int i = 0; i < content.length(); i++) {
                if (content.charAt(i) == '\n') n++;
            }
            return n;
        }

        public String statusLabel() {
            switch (status) {
                case VALIDATED: return "✅";
                case REJECTED: return "❌";
                default: return "⏳";
            }
        }
    }

    private String sessionName;
    private final List<OrionFile> files = new ArrayList<>();
    private final long createdAt;
    private String suggestedCommitMessage = "";
    private String lastCommitMessage = "";
    private long lastCommitAt;

    public OrionFileSession(String name) {
        this.sessionName = name == null || name.trim().isEmpty() ? "session" : name.trim();
        this.createdAt = System.currentTimeMillis();
    }

    public String getSessionName() {
        return sessionName;
    }

    public void setSessionName(String name) {
        if (name != null && !name.trim().isEmpty()) sessionName = name.trim();
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public synchronized List<OrionFile> getFiles() {
        return Collections.unmodifiableList(new ArrayList<>(files));
    }

    public synchronized int size() {
        return files.size();
    }

    public synchronized boolean isEmpty() {
        return files.isEmpty();
    }

    public synchronized void addFile(String path, String content) {
        String p = sanitizePath(path);
        String body = content == null ? "" : content;
        for (OrionFile f : files) {
            if (f.path.equalsIgnoreCase(p)) {
                f.content = body;
                f.status = FileStatus.PENDING;
                return;
            }
        }
        files.add(new OrionFile(p, body, FileStatus.PENDING));
    }

    public synchronized void addAll(List<OrionFileParser.ParsedFile> parsed) {
        if (parsed == null) return;
        for (OrionFileParser.ParsedFile p : parsed) {
            if (p == null) continue;
            addFile(p.path, p.content);
        }
        collapseDuplicateNames();
    }

    /**
     * Une entrée par nom (insensible à la casse) : contenu de la dernière
     * occurrence, ordre des noms = première apparition — comme
     * {@code GeneratedFiles.collapseDuplicateNames}.
     */
    public synchronized void collapseDuplicateNames() {
        if (files.size() <= 1) return;
        Map<String, OrionFile> byName = new LinkedHashMap<>();
        for (OrionFile f : files) {
            if (f == null || f.path == null || f.path.isEmpty()) continue;
            byName.put(f.path.toLowerCase(Locale.ROOT), f);
        }
        if (byName.size() == files.size()) return;
        files.clear();
        files.addAll(byName.values());
    }

    public synchronized void validateAll() {
        for (OrionFile f : files) {
            if (f.status == FileStatus.PENDING) f.status = FileStatus.VALIDATED;
        }
    }

    public synchronized boolean setStatus(String path, FileStatus status) {
        OrionFile f = find(path);
        if (f == null || status == null) return false;
        f.status = status;
        return true;
    }

    public synchronized boolean updateContent(String path, String content) {
        OrionFile f = find(path);
        if (f == null) return false;
        f.content = content == null ? "" : content;
        return true;
    }

    public synchronized OrionFile find(String path) {
        if (path == null) return null;
        String p = sanitizePath(path);
        for (OrionFile f : files) {
            if (f.path.equalsIgnoreCase(p) || f.path.equalsIgnoreCase(path.trim())) {
                return f;
            }
        }
        return null;
    }

    public synchronized List<OrionFile> getReadyFiles() {
        List<OrionFile> out = new ArrayList<>();
        for (OrionFile f : files) {
            if (f.status == FileStatus.VALIDATED) out.add(f);
        }
        return out;
    }

    public synchronized List<OrionFile> getPendingFiles() {
        List<OrionFile> out = new ArrayList<>();
        for (OrionFile f : files) {
            if (f.status == FileStatus.PENDING) out.add(f);
        }
        return out;
    }

    public synchronized void clear() {
        files.clear();
        suggestedCommitMessage = "";
    }

    public String getSuggestedCommitMessage() {
        return suggestedCommitMessage;
    }

    public void setSuggestedCommitMessage(String msg) {
        this.suggestedCommitMessage = msg == null ? "" : msg.trim();
    }

    public String getLastCommitMessage() {
        return lastCommitMessage;
    }

    public long getLastCommitAt() {
        return lastCommitAt;
    }

    public void recordCommit(String message) {
        this.lastCommitMessage = message == null ? "" : message.trim();
        this.lastCommitAt = System.currentTimeMillis();
    }

    /** Message de commit par défaut à partir des chemins. */
    public synchronized String defaultCommitMessage() {
        if (!suggestedCommitMessage.isEmpty()) return suggestedCommitMessage;
        List<OrionFile> ready = getReadyFiles();
        if (ready.isEmpty()) ready = new ArrayList<>(files);
        if (ready.isEmpty()) return "feat(" + slug(sessionName) + "): update";
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(ready.size(), 4); i++) {
            if (i > 0) names.append(" + ");
            names.append(ready.get(i).path);
        }
        if (ready.size() > 4) names.append("…");
        return "feat(" + slug(sessionName) + "): " + names;
    }

    public synchronized String speakSummary() {
        if (files.isEmpty()) return "Aucun fichier dans la session Orion.";
        int pending = 0, ok = 0, rej = 0;
        for (OrionFile f : files) {
            if (f.status == FileStatus.PENDING) pending++;
            else if (f.status == FileStatus.VALIDATED) ok++;
            else rej++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Session « ").append(sessionName).append(" » : ")
                .append(files.size()).append(" fichier")
                .append(files.size() > 1 ? "s" : "").append(" — ");
        sb.append(ok).append(" validé").append(ok > 1 ? "s" : "");
        if (pending > 0) sb.append(", ").append(pending).append(" en attente");
        if (rej > 0) sb.append(", ").append(rej).append(" rejeté").append(rej > 1 ? "s" : "");
        sb.append(". ");
        for (int i = 0; i < files.size(); i++) {
            OrionFile f = files.get(i);
            if (i > 0) sb.append(" · ");
            sb.append(f.statusLabel()).append(' ').append(f.path);
        }
        return sb.toString();
    }

    private static String sanitizePath(String path) {
        if (path == null || path.trim().isEmpty()) return "output.txt";
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        if (p.contains("..")) p = p.replace("..", "");
        // keep relative path segments if safe
        p = p.replaceAll("[^a-zA-Z0-9._/\\-]", "_");
        if (p.isEmpty()) return "output.txt";
        if (!p.contains(".")) p = p + ".txt";
        return p;
    }

    private static String slug(String name) {
        String s = name == null ? "orion" : name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.isEmpty()) return "orion";
        if (s.length() > 24) s = s.substring(0, 24);
        return s;
    }
}
