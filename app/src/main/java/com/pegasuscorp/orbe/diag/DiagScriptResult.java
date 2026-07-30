package com.pegasuscorp.orbe.diag;

import java.io.File;

/** Résultat d'une suite mini-tests (stress isolé dans trace.jsonl). */
public final class DiagScriptResult {

    public final int stepsTotal;
    public final int stepsOk;
    public final int stepsError;
    public final int stepsTimeout;
    public final int stepsSkipped;
    public final int anomaliesStress;
    public final int eventsStress;
    public final long llmP95Ms;
    public final long durationMs;
    public final File reportFile;
    public final boolean clean;

    public DiagScriptResult(int stepsTotal, int stepsOk, int stepsError, int stepsTimeout,
            int stepsSkipped, int anomaliesStress, int eventsStress, long llmP95Ms,
            long durationMs, File reportFile) {
        this.stepsTotal = stepsTotal;
        this.stepsOk = stepsOk;
        this.stepsError = stepsError;
        this.stepsTimeout = stepsTimeout;
        this.stepsSkipped = stepsSkipped;
        this.anomaliesStress = anomaliesStress;
        this.eventsStress = eventsStress;
        this.llmP95Ms = llmP95Ms;
        this.durationMs = durationMs;
        this.reportFile = reportFile;
        this.clean = anomaliesStress == 0 && stepsError == 0 && stepsTimeout == 0;
    }

    public String summaryLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(stepsOk).append('/').append(stepsTotal).append(" OK");
        if (stepsSkipped > 0) sb.append(" · ").append(stepsSkipped).append(" ignoré(s)");
        if (stepsError > 0) sb.append(" · ").append(stepsError).append(" erreur(s)");
        if (stepsTimeout > 0) sb.append(" · ").append(stepsTimeout).append(" timeout");
        sb.append(" · ").append(anomaliesStress).append(" anomalie(s) trace");
        if (llmP95Ms > 0) sb.append(" · P95 ").append(llmP95Ms).append(" ms");
        return sb.toString();
    }
}
