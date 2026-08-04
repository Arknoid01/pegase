package com.pegasuscorp.orbe.memory;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.rag.EmbeddingEngine;
import com.pegasuscorp.orbe.rag.MemoryRagMigrator;
import com.pegasuscorp.orbe.rag.VectorStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mémoire locale : tours récents, résumés de session, souvenirs permanents + RAG sémantique.
 */
public class MemoryRepository implements MemoryStore {

    private static final String TAG = "MemoryRepository";
    private static final String PREFS = "memory_repository";
    private static final String KEY_LAST_DECAY_MS = "last_decay_ms";
    private static final long DECAY_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    private static final int MAX_TURNS = ConversationHistorySanitizer.MAX_STORED_TURNS;
    private static final int MAX_SESSION_SUMMARIES = 12;
    private static final int MAX_RELEVANT_MEMORIES = 5;

    /** Seuil cosine typique après mean-pool MiniLM (Phase 1 : frigot≈0.36). */
    public static final float SEMANTIC_MIN_SCORE = 0.30f;

    private static MemoryRepository instance;
    /** Désactiver en tests unitaires pour éviter ORT concurrent avec migrateAsync. */
    private static volatile boolean autoMigrate = true;

    private final Context appContext;
    private final File memoryDir;
    private final File permanentFile;
    private final File sessionsFile;
    private final File turnsFile;
    private final ExecutorService ragIo = Executors.newSingleThreadExecutor();

    private List<MemoryEntry> permanentMemories = new ArrayList<>();
    private List<SessionSummary> sessionSummaries = new ArrayList<>();
    private List<ChatBackend.Turn> recentTurns = new ArrayList<>();

    private volatile VectorStore vectorStore;

    private MemoryRepository(Context context) {
        appContext = context.getApplicationContext();
        memoryDir = new File(appContext.getFilesDir(), "memory");
        if (!memoryDir.exists()) memoryDir.mkdirs();
        permanentFile = new File(memoryDir, "permanent.json");
        sessionsFile = new File(memoryDir, "sessions.json");
        turnsFile = new File(memoryDir, "recent_turns.json");
        loadAll();
        purgeEphemeralNoise();
        seedDefaultsIfEmpty();
        backfillGraphLinks();
        applyNaturalDecayIfDue(System.currentTimeMillis());
        if (autoMigrate) {
            MemoryRagMigrator.migrateAsync(appContext, this);
        }
    }

    public static void setAutoMigrateForTests(boolean enabled) {
        autoMigrate = enabled;
    }

    public static synchronized MemoryRepository getInstance(Context context) {
        if (instance == null) instance = new MemoryRepository(context);
        return instance;
    }

    /** Tests : force une nouvelle instance propre. */
    public static synchronized void resetInstanceForTests() {
        if (instance != null) {
            try {
                instance.ragIo.shutdownNow();
            } catch (Exception ignored) {}
            try {
                if (instance.vectorStore != null) instance.vectorStore.close();
            } catch (Exception ignored) {}
            instance = null;
        }
    }

    @Override
    public List<ChatBackend.Turn> getRecentTurns() {
        return new ArrayList<>(recentTurns);
    }

    /** Recharge recent_turns.json depuis le disque (onglet Discussion). */
    public synchronized void reloadRecentTurnsFromDisk() {
        recentTurns = readTurns();
        notifyTurnsChanged();
    }

    @Override
    public void setRecentTurns(List<ChatBackend.Turn> turns) {
        // Ne pas dropper un tour user en attente — sinon la bulle disparaît à l'écran.
        recentTurns = ConversationHistorySanitizer.normalizeKeepingTrailingUser(turns);
        saveTurns();
        notifyTurnsChanged();
    }

    @Override
    public void clearRecentTurns() {
        recentTurns.clear();
        saveTurns();
        notifyTurnsChanged();
    }

    /**
     * Un historique qui se termine par un tour utilisateur = demande "en attente" pour le LLM,
     * qui la ré-exécutera au tour suivant. On ne persiste jamais ça.
     */
    private static void dropTrailingUserTurns(List<ChatBackend.Turn> turns) {
        ConversationHistorySanitizer.dropTrailingUserTurns(turns);
    }

    @Override
    public void addTurn(boolean fromUser, String text) {
        String stored = ConversationHistorySanitizer.forStorage(fromUser, text);
        if (stored.isEmpty()) return;
        if (fromUser && !recentTurns.isEmpty() && recentTurns.get(recentTurns.size() - 1).fromUser) {
            recentTurns.set(recentTurns.size() - 1, new ChatBackend.Turn(true, stored));
        } else {
            recentTurns.add(new ChatBackend.Turn(fromUser, stored));
        }
        while (recentTurns.size() > MAX_TURNS) {
            recentTurns.remove(0);
        }
        saveTurns();
        notifyTurnsChanged();
    }

    @Override
    public void replaceLastUserTurn(String text) {
        String stored = ConversationHistorySanitizer.forUser(text);
        if (stored.isEmpty()) return;
        if (recentTurns.isEmpty() || !recentTurns.get(recentTurns.size() - 1).fromUser) {
            addTurn(true, stored);
            return;
        }
        recentTurns.set(recentTurns.size() - 1, new ChatBackend.Turn(true, stored));
        saveTurns();
        notifyTurnsChanged();
    }

    /**
     * Remplace le tour assistant de l'échange EN COURS, c'est-à-dire celui situé APRÈS le
     * dernier tour utilisateur. S'il n'existe pas encore (appel d'outil sans préambule),
     * on ajoute un nouveau tour au lieu de remonter l'historique et d'écraser la réponse
     * de l'échange précédent (bug de la boucle de répétition).
     */
    @Override
    public void replaceLastAssistantTurn(String text) {
        String stored = ConversationHistorySanitizer.forAssistant(text);
        if (stored.isEmpty()) return;
        int lastUser = -1;
        for (int i = recentTurns.size() - 1; i >= 0; i--) {
            if (recentTurns.get(i).fromUser) { lastUser = i; break; }
        }
        for (int i = recentTurns.size() - 1; i > lastUser; i--) {
            if (!recentTurns.get(i).fromUser) {
                recentTurns.set(i, new ChatBackend.Turn(false, stored));
                saveTurns();
                notifyTurnsChanged();
                return;
            }
        }
        addTurn(false, stored);
    }

    public interface OnTurnsChangedListener {
        void onTurnsChanged();
    }

    private OnTurnsChangedListener turnsListener;

    public void setOnTurnsChangedListener(OnTurnsChangedListener listener) {
        turnsListener = listener;
    }

    public OnTurnsChangedListener getOnTurnsChangedListener() {
        return turnsListener;
    }

    private void notifyTurnsChanged() {
        if (turnsListener != null) turnsListener.onTurnsChanged();
    }

    public SessionSummary getLatestSessionSummary() {
        if (sessionSummaries.isEmpty()) return null;
        return sessionSummaries.get(sessionSummaries.size() - 1);
    }

    public void addSessionSummary(SessionSummary summary) {
        if (summary == null) return;
        if (summary.endedAt == null || summary.endedAt.isEmpty()) {
            summary.endedAt = today();
        }
        sessionSummaries.add(summary);
        while (sessionSummaries.size() > MAX_SESSION_SUMMARIES) {
            sessionSummaries.remove(0);
        }
        saveSessions();
        MemoryConsolidator.promoteSessionFacts(appContext, summary);
    }

    public List<MemoryEntry> getRelevantMemories(String query, int max) {
        return getRelevantMemories(query, null, max, 0.0);
    }

    public List<MemoryEntry> getRelevantMemories(String query, List<String> entityTerms,
            int max, double minScore) {
        applyNaturalDecayIfDue(System.currentTimeMillis());
        if (permanentMemories.isEmpty()) return Collections.emptyList();
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<ScoredMemory> scored = new ArrayList<>();
        for (MemoryEntry entry : permanentMemories) {
            if (!isInjectable(entry)) continue;
            double s = MemoryScorer.keywordScore(entry, q, entityTerms);
            if (s >= minScore) scored.add(new ScoredMemory(entry, s));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        int limit = Math.min(max, MAX_RELEVANT_MEMORIES);
        List<MemoryEntry> out = new ArrayList<>();
        for (int i = 0; i < scored.size() && out.size() < limit; i++) {
            out.add(scored.get(i).entry);
        }
        recordMemoryRetrievalUse(out);
        return out;
    }

    /**
     * Recherche sémantique (cosine MiniLM). Repli sur le scoring mots-clés si l'index
     * est vide ou si l'embedding échoue.
     */
    public List<MemoryEntry> getRelevantMemoriesSemantic(String query, int max, float minScore) {
        return getRelevantMemoriesSemantic(query, null, max, minScore);
    }

    public List<MemoryEntry> getRelevantMemoriesSemantic(String query, List<String> entityTerms,
            int max, float minScore) {
        return getRelevantMemoriesSemantic(query, entityTerms, null, max, minScore);
    }

    public List<MemoryEntry> getRelevantMemoriesSemantic(String query, List<String> entityTerms,
            List<String> seedEntityIds, int max, float minScore) {
        applyNaturalDecayIfDue(System.currentTimeMillis());
        if (permanentMemories.isEmpty()) return Collections.emptyList();
        int limit = Math.min(max, MAX_RELEVANT_MEMORIES);
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Map<String, Float> cosineByKey = new HashMap<>();
        EntityGraphStore entityGraph = EntityGraphStore.getInstance(appContext);
        EntityGraphStore.EntityReach entityReach = entityGraph.expand(
                seedEntityIds != null ? seedEntityIds : Collections.emptyList(), 2);
        entityGraph.recordRetrievalUse(entityReach);
        try {
            float[] qv = EmbeddingEngine.get(appContext).embed(query == null ? "" : query);
            List<VectorStore.Hit> hits = vectors().search(qv, Math.max(limit * 3, 8), minScore);
            Map<String, MemoryEntry> byKey = indexByKey();
            List<ScoredMemory> scored = new ArrayList<>();
            for (VectorStore.Hit hit : hits) {
                cosineByKey.put(hit.memoryKey, hit.score);
                MemoryEntry entry = byKey.get(hit.memoryKey);
                if (entry != null && isInjectable(entry)) {
                    double composite = MemoryScorer.compositeSemantic(
                            entry, hit.score, entityTerms, entityReach);
                    scored.add(new ScoredMemory(entry, composite));
                }
            }
            if (!scored.isEmpty()) {
                scored.sort((a, b) -> Double.compare(b.score, a.score));
                List<MemoryEntry> ranked = new ArrayList<>();
                for (ScoredMemory sm : scored) ranked.add(sm.entry);
                return finalizeGraphRanked(ranked, q, entityTerms, entityReach, limit,
                        cosineByKey);
            }
        } catch (Exception e) {
            Log.w(TAG, "Recherche sémantique indisponible, fallback mots-clés", e);
        }
        return getRelevantMemories(query, entityTerms, limit, 0.58);
    }

    private List<MemoryEntry> finalizeGraphRanked(List<MemoryEntry> ranked, String queryLower,
            List<String> entityTerms, EntityGraphStore.EntityReach entityReach, int limit,
            Map<String, Float> cosineByKey) {
        List<MemoryEntry> expanded = MemoryGraph.expandCandidates(
                ranked, permanentMemories, entityReach.allWithin(2),
                Math.max(limit * 2, limit + 1));
        List<ScoredMemory> rescored = new ArrayList<>();
        for (MemoryEntry entry : expanded) {
            float cosine = cosineByKey.containsKey(entry.memoryKey())
                    ? cosineByKey.get(entry.memoryKey()) : 0f;
            double score = cosine > 0
                    ? MemoryScorer.compositeSemantic(entry, cosine, entityTerms, entityReach)
                    : MemoryScorer.keywordScore(entry, queryLower, entityTerms)
                            + MemoryScorer.graphEntityBoost(entry, entityReach);
            rescored.add(new ScoredMemory(entry, score));
        }
        rescored.sort((a, b) -> Double.compare(b.score, a.score));
        List<MemoryEntry> out = new ArrayList<>();
        for (int i = 0; i < rescored.size() && out.size() < limit; i++) {
            out.add(rescored.get(i).entry);
        }
        recordMemoryRetrievalUse(out);
        return out;
    }

    void applyNaturalDecay(long nowMs) {
        boolean changed = false;
        for (MemoryEntry entry : permanentMemories) {
            if (entry.applyDecay(nowMs)) changed = true;
        }
        if (changed) savePermanent();
        prefs().edit().putLong(KEY_LAST_DECAY_MS, nowMs).apply();
    }

    private void applyNaturalDecayIfDue(long nowMs) {
        long last = prefs().getLong(KEY_LAST_DECAY_MS, 0L);
        if (nowMs - last < DECAY_INTERVAL_MS) return;
        applyNaturalDecay(nowMs);
    }

    private void recordMemoryRetrievalUse(List<MemoryEntry> used) {
        if (used == null || used.isEmpty()) return;
        long now = System.currentTimeMillis();
        Set<String> keys = new HashSet<>();
        for (MemoryEntry entry : used) {
            if (entry != null) keys.add(entry.memoryKey());
        }
        boolean changed = false;
        for (MemoryEntry entry : permanentMemories) {
            if (!keys.contains(entry.memoryKey())) continue;
            entry.touchRetrieval(now);
            changed = true;
        }
        if (changed) savePermanent();
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void addPermanentMemory(MemoryEntry entry) {
        if (entry == null) return;
        if (EphemeralMemoryFilter.isNoise(entry.content)) {
            Log.d(TAG, "Souvenir éphemère ignoré: " + entry.content);
            return;
        }
        MemoryLinker.autoLink(appContext, entry);
        for (MemoryEntry existing : permanentMemories) {
            MemoryGraph.linkSharedEntities(entry, existing);
        }
        permanentMemories.add(entry);
        savePermanent();
        indexMemoryAsync(entry);
    }

    /**
     * Souvenirs {@code source=fallback} (120b / Qwen) : stockés mais exclus du contexte LLM.
     */
    static boolean isInjectable(MemoryEntry entry) {
        return entry != null && !entry.isFallbackSource();
    }

    public List<MemoryEntry> getAllPermanentMemories() {
        return new ArrayList<>(permanentMemories);
    }

    /**
     * Souvenirs liés (graphe mémoire) — clés partagées ou entités communes.
     */
    public List<MemoryEntry> getLinkedMemories(MemoryEntry entry) {
        if (entry == null) return Collections.emptyList();
        String key = entry.memoryKey();
        List<MemoryEntry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(key);
        for (MemoryEntry other : permanentMemories) {
            if (other == null || !isInjectable(other)) continue;
            String otherKey = other.memoryKey();
            if (seen.contains(otherKey)) continue;
            boolean linked = entry.relatedMemoryKeys.contains(otherKey)
                    || other.relatedMemoryKeys.contains(key);
            if (!linked) {
                for (String id : entry.entityIds) {
                    if (other.entityIds.contains(id)) {
                        linked = true;
                        break;
                    }
                }
            }
            if (linked) {
                seen.add(otherKey);
                out.add(other);
            }
        }
        out.sort((a, b) -> Double.compare(b.effectiveImportance(), a.effectiveImportance()));
        return out;
    }

    /** Souvenirs permanents triés par importance (pour affichage Discussion). */
    public List<MemoryEntry> getTopPermanentMemories(int max) {
        if (max <= 0 || permanentMemories.isEmpty()) return Collections.emptyList();
        List<MemoryEntry> sorted = new ArrayList<>(permanentMemories);
        sorted.sort((a, b) -> Double.compare(b.effectiveImportance(), a.effectiveImportance()));
        if (sorted.size() <= max) return sorted;
        return new ArrayList<>(sorted.subList(0, max));
    }

    public void updatePermanentMemoryAt(int index, MemoryEntry entry) {
        if (entry == null || index < 0 || index >= permanentMemories.size()) return;
        MemoryEntry old = permanentMemories.get(index);
        MemoryLinker.autoLink(appContext, entry);
        for (int i = 0; i < permanentMemories.size(); i++) {
            if (i == index) continue;
            MemoryGraph.linkSharedEntities(entry, permanentMemories.get(i));
        }
        permanentMemories.set(index, entry);
        savePermanent();
        deleteVectorAsync(old);
        indexMemoryAsync(entry);
    }

    public void removePermanentMemoryAt(int index) {
        if (index < 0 || index >= permanentMemories.size()) return;
        MemoryEntry removed = permanentMemories.remove(index);
        savePermanent();
        deleteVectorAsync(removed);
    }

    public List<SessionSummary> getAllSessionSummaries() {
        return new ArrayList<>(sessionSummaries);
    }

    public void removeSessionSummaryAt(int index) {
        if (index < 0 || index >= sessionSummaries.size()) return;
        sessionSummaries.remove(index);
        saveSessions();
    }

    public int removePermanentContaining(String query) {
        if (query == null || query.isEmpty()) return 0;
        String q = query.toLowerCase(Locale.ROOT);
        List<MemoryEntry> removed = new ArrayList<>();
        permanentMemories.removeIf(e -> {
            boolean match = e.content != null && e.content.toLowerCase(Locale.ROOT).contains(q);
            if (match) removed.add(e);
            return match;
        });
        if (!removed.isEmpty()) {
            savePermanent();
            for (MemoryEntry e : removed) deleteVectorAsync(e);
        }
        return removed.size();
    }

    public int replaceInPermanent(String search, String replacement) {
        if (search == null || search.isEmpty()) return 0;
        int count = 0;
        for (MemoryEntry e : permanentMemories) {
            if (e.content != null && e.content.contains(search)) {
                String oldKey = VectorStore.keyFor(e.category, e.content);
                e.content = e.content.replace(search, replacement);
                count++;
                reindexAfterContentChangeAsync(oldKey, e);
            }
        }
        if (count > 0) savePermanent();
        return count;
    }

    private VectorStore vectors() {
        if (vectorStore == null) {
            synchronized (this) {
                if (vectorStore == null) {
                    vectorStore = new VectorStore(appContext);
                }
            }
        }
        return vectorStore;
    }

    /** Indexe tous les souvenirs manquants (sync — migration / tests). */
    public int indexAllMissingNow() {
        int indexed = 0;
        try {
            EmbeddingEngine engine = EmbeddingEngine.get(appContext);
            VectorStore store = vectors();
            for (MemoryEntry entry : permanentMemories) {
                if (entry == null || entry.content == null || entry.content.isEmpty()) continue;
                String key = VectorStore.keyFor(entry.category, entry.content);
                if (store.hasVector(key)) continue;
                float[] vector = engine.embed(entry.content);
                store.upsert(key, vector);
                indexed++;
            }
        } catch (Exception e) {
            Log.w(TAG, "indexAllMissingNow", e);
        }
        return indexed;
    }

    private Map<String, MemoryEntry> indexByKey() {
        Map<String, MemoryEntry> map = new HashMap<>();
        for (MemoryEntry e : permanentMemories) {
            if (e == null) continue;
            map.put(VectorStore.keyFor(e.category, e.content), e);
        }
        return map;
    }

    private void indexMemoryAsync(MemoryEntry entry) {
        if (entry == null) return;
        // En mode test (autoMigrate off) : index synchrone pour éviter ORT + reset race.
        if (!autoMigrate) {
            indexMemoryNow(entry);
            return;
        }
        MemoryEntry snapshot = entry;
        ragIo.execute(() -> indexMemoryNow(snapshot));
    }

    private void indexMemoryNow(MemoryEntry entry) {
        try {
            String key = VectorStore.keyFor(entry.category, entry.content);
            float[] vector = EmbeddingEngine.get(appContext).embed(
                    entry.content == null ? "" : entry.content);
            vectors().upsert(key, vector);
        } catch (Exception e) {
            Log.w(TAG, "Index RAG échoué", e);
        }
    }

    private void deleteVectorAsync(MemoryEntry entry) {
        if (entry == null) return;
        String key = VectorStore.keyFor(entry.category, entry.content);
        if (!autoMigrate) {
            try {
                vectors().delete(key);
            } catch (Exception e) {
                Log.w(TAG, "Delete vector échoué", e);
            }
            return;
        }
        ragIo.execute(() -> {
            try {
                vectors().delete(key);
            } catch (Exception e) {
                Log.w(TAG, "Delete vector échoué", e);
            }
        });
    }

    private void reindexAfterContentChangeAsync(String oldKey, MemoryEntry entry) {
        MemoryEntry snapshot = entry;
        ragIo.execute(() -> {
            try {
                vectors().delete(oldKey);
                String key = VectorStore.keyFor(snapshot.category, snapshot.content);
                float[] vector = EmbeddingEngine.get(appContext).embed(
                        snapshot.content == null ? "" : snapshot.content);
                vectors().upsert(key, vector);
            } catch (Exception e) {
                Log.w(TAG, "Reindex RAG échoué", e);
            }
        });
    }

    private static final class ScoredMemory {
        final MemoryEntry entry;
        final double score;

        ScoredMemory(MemoryEntry entry, double score) {
            this.entry = entry;
            this.score = score;
        }
    }

    private void seedDefaultsIfEmpty() {
        if (!permanentMemories.isEmpty()) return;
        MemoryEntry pegase = new MemoryEntry(
                "project",
                "Yannick développe Pégase, un assistant vocal Android intégré au launcher Orbe.",
                0.95,
                today());
        pegase.frozen = true;
        MemoryEntry fableris = new MemoryEntry(
                "project",
                "Yannick travaille sur Fableris, un city builder nommé Fableris.",
                0.9,
                today());
        fableris.frozen = true;
        permanentMemories.add(pegase);
        permanentMemories.add(fableris);
        for (MemoryEntry e : permanentMemories) {
            MemoryLinker.autoLink(appContext, e);
        }
        for (int i = 0; i < permanentMemories.size(); i++) {
            for (int j = i + 1; j < permanentMemories.size(); j++) {
                MemoryGraph.linkSharedEntities(permanentMemories.get(i), permanentMemories.get(j));
            }
        }
        savePermanent();
    }

    private void backfillGraphLinks() {
        boolean changed = false;
        for (MemoryEntry e : permanentMemories) {
            int before = e.entityIds.size();
            if (before == 0) {
                MemoryLinker.autoLink(appContext, e);
                if (!e.entityIds.isEmpty()) changed = true;
            }
        }
        for (int i = 0; i < permanentMemories.size(); i++) {
            for (int j = i + 1; j < permanentMemories.size(); j++) {
                MemoryEntry a = permanentMemories.get(i);
                MemoryEntry b = permanentMemories.get(j);
                int relBefore = a.relatedMemoryKeys.size() + b.relatedMemoryKeys.size();
                MemoryGraph.linkSharedEntities(a, b);
                if (a.relatedMemoryKeys.size() + b.relatedMemoryKeys.size() > relBefore) {
                    changed = true;
                }
            }
        }
        if (changed) savePermanent();
    }

    private void loadAll() {
        permanentMemories = readPermanent();
        sessionSummaries = readSessions();
        recentTurns = readTurns();
    }

    /** Retire les accusés d'outils déjà stockés par erreur (clics UI, etc.). */
    private void purgeEphemeralNoise() {
        List<MemoryEntry> kept = new ArrayList<>();
        List<MemoryEntry> dropped = new ArrayList<>();
        for (MemoryEntry e : permanentMemories) {
            if (e != null && EphemeralMemoryFilter.isNoise(e.content)) {
                dropped.add(e);
            } else if (e != null) {
                kept.add(e);
            }
        }
        if (dropped.isEmpty()) return;
        permanentMemories = kept;
        savePermanent();
        for (MemoryEntry e : dropped) {
            deleteVectorAsync(e);
            Log.d(TAG, "Purge éphémère: " + e.content);
        }
    }

    private List<MemoryEntry> readPermanent() {
        JSONArray arr = readJsonArray(permanentFile);
        List<MemoryEntry> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(MemoryEntry.fromJson(o));
        }
        return out;
    }

    private List<SessionSummary> readSessions() {
        JSONArray arr = readJsonArray(sessionsFile);
        List<SessionSummary> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(SessionSummary.fromJson(o));
        }
        return out;
    }

    private List<ChatBackend.Turn> readTurns() {
        JSONArray arr = readJsonArray(turnsFile);
        List<ChatBackend.Turn> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            out.add(new ChatBackend.Turn(o.optBoolean("fromUser"), o.optString("text", "")));
        }
        return out;
    }

    private void savePermanent() {
        writeJsonArray(permanentFile, entriesToJson(permanentMemories));
    }

    private void saveSessions() {
        JSONArray arr = new JSONArray();
        for (SessionSummary s : sessionSummaries) {
            try {
                arr.put(s.toJson());
            } catch (Exception ignored) {}
        }
        writeJsonArray(sessionsFile, arr);
    }

    private void saveTurns() {
        JSONArray arr = new JSONArray();
        for (ChatBackend.Turn t : recentTurns) {
            try {
                arr.put(new JSONObject()
                        .put("fromUser", t.fromUser)
                        .put("text", t.text));
            } catch (Exception ignored) {}
        }
        writeJsonArray(turnsFile, arr);
    }

    private JSONArray entriesToJson(List<MemoryEntry> entries) {
        JSONArray arr = new JSONArray();
        for (MemoryEntry e : entries) {
            try {
                arr.put(e.toJson());
            } catch (Exception ignored) {}
        }
        return arr;
    }

    private JSONArray readJsonArray(File file) {
        if (!file.exists()) return new JSONArray();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return new JSONArray(sb.toString());
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void writeJsonArray(File file, JSONArray arr) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }
}
