package com.pegasuscorp.orbe.orion;

import java.util.Collections;
import java.util.List;

/** État d'une session de découpage séquentiel Orion. */
public final class OrionChunkSession {

    public final List<TaskChunk> chunks;
    private int currentIndex;

    public OrionChunkSession(List<TaskChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks required");
        }
        this.chunks = Collections.unmodifiableList(chunks);
        this.currentIndex = 0;
    }

    public TaskChunk current() {
        return chunks.get(currentIndex);
    }

    public boolean hasNext() {
        return currentIndex < chunks.size() - 1;
    }

    public TaskChunk next() {
        if (!hasNext()) return current();
        currentIndex++;
        return current();
    }

    public boolean isComplete() {
        return currentIndex >= chunks.size();
    }

    /** Pourcentage de progression (étape courante / total). */
    public int progressPercent() {
        if (chunks.isEmpty()) return 0;
        return (current().index * 100) / chunks.size();
    }
}
