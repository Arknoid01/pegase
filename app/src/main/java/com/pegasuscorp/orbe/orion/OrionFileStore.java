package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Session Orion courante (fichiers à review / committer).
 * Singleton processus — survit aux changements d'onglet.
 */
public final class OrionFileStore {

    public interface Observer {
        void onSessionChanged();
    }

    private static final OrionFileStore INSTANCE = new OrionFileStore();

    private OrionFileSession currentSession;
    private final List<String> commitHistory = new ArrayList<>();
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();

    private OrionFileStore() {}

    public static OrionFileStore get() {
        return INSTANCE;
    }

    public void addObserver(Observer o) {
        if (o != null) observers.addIfAbsent(o);
    }

    public void removeObserver(Observer o) {
        if (o != null) observers.remove(o);
    }

    private void notifyChanged() {
        for (Observer o : observers) {
            try {
                o.onSessionChanged();
            } catch (Exception ignored) {
            }
        }
    }

    /** Après un lint async — rafraîchir le panneau session. */
    public void notifyLintUpdated() {
        notifyChanged();
    }

    public synchronized OrionFileSession getCurrentSession() {
        return currentSession;
    }

    public synchronized boolean hasSession() {
        return currentSession != null && !currentSession.isEmpty();
    }

    public synchronized void newSession(String name) {
        currentSession = new OrionFileSession(name);
        notifyChanged();
    }

    public synchronized void clearSession() {
        if (currentSession != null) currentSession.clear();
        currentSession = null;
        notifyChanged();
    }

    public synchronized void addFileToSession(String path, String content) {
        if (currentSession == null) newSession("session Orion");
        currentSession.addFile(path, content);
        currentSession.collapseDuplicateNames();
        notifyChanged();
    }

    /**
     * Parse la sortie Orion, remplit / remplace la session, et sauve aussi sur disque.
     * @return fichiers de la session
     */
    public synchronized List<OrionFileSession.OrionFile> ingestOrionOutput(
            Context ctx, String fullText, String sessionHint) {
        List<OrionFileParser.ParsedFile> parsed = OrionFileParser.parse(fullText);
        String name = !TextUtils.isEmpty(sessionHint)
                ? sessionHint.trim()
                : guessSessionName(parsed);
        if (currentSession == null || currentSession.isEmpty()) {
            currentSession = new OrionFileSession(name);
        } else {
            currentSession.setSessionName(name);
        }
        currentSession.addAll(parsed);
        try {
            GeneratedFiles.autoSaveOrionResponse(ctx, fullText);
        } catch (Exception ignored) {
        }
        notifyChanged();
        return currentSession.getFiles();
    }

    public synchronized void validateAll() {
        if (currentSession != null) {
            currentSession.validateAll();
            notifyChanged();
        }
    }

    public synchronized boolean setStatus(String path, OrionFileSession.FileStatus status) {
        boolean ok = currentSession != null && currentSession.setStatus(path, status);
        if (ok) notifyChanged();
        return ok;
    }

    public synchronized boolean updateContent(String path, String content) {
        if (currentSession == null) return false;
        boolean ok = currentSession.updateContent(path, content);
        if (ok) notifyChanged();
        return ok;
    }

    public synchronized List<OrionFileSession.OrionFile> getReadyFiles() {
        if (currentSession == null) return new ArrayList<>();
        return currentSession.getReadyFiles();
    }

    public synchronized String speakSummary() {
        if (currentSession == null) return "Pas de session Orion active.";
        return currentSession.speakSummary();
    }

    public synchronized String defaultCommitMessage() {
        if (currentSession == null) return "feat(orion): update";
        return currentSession.defaultCommitMessage();
    }

    public synchronized void setSuggestedCommitMessage(String msg) {
        if (currentSession != null) {
            currentSession.setSuggestedCommitMessage(msg);
            notifyChanged();
        }
    }

    public synchronized void recordCommit(String message) {
        if (currentSession != null) currentSession.recordCommit(message);
        if (message != null && !message.trim().isEmpty()) {
            commitHistory.add(0, message.trim());
            while (commitHistory.size() > 10) {
                commitHistory.remove(commitHistory.size() - 1);
            }
        }
        notifyChanged();
    }

    public synchronized List<String> getCommitHistory() {
        return new ArrayList<>(commitHistory);
    }

    private static String guessSessionName(List<OrionFileParser.ParsedFile> parsed) {
        if (parsed == null || parsed.isEmpty()) return "session Orion";
        String first = parsed.get(0).path;
        int dot = first.lastIndexOf('.');
        String base = dot > 0 ? first.substring(0, dot) : first;
        if (parsed.size() == 1) return base;
        return base + " +" + (parsed.size() - 1);
    }
}
