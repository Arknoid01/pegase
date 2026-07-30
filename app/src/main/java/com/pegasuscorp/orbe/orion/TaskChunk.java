package com.pegasuscorp.orbe.orion;

import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;

/** Une étape d'une mission LARGE/MASSIVE découpée par {@link TaskChunker}. */
public final class TaskChunk {

    public final int index;
    public final int total;
    public final ResolvedTask task;
    public final String summary;

    public TaskChunk(int index, int total, ResolvedTask task, String summary) {
        this.index = Math.max(1, index);
        this.total = Math.max(1, total);
        this.task = task;
        this.summary = summary != null ? summary.trim() : "";
    }

    public String toProgressLabel() {
        return "Étape " + index + "/" + total + " — " + summary;
    }
}
