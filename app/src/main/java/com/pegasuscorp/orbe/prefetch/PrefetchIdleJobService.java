package com.pegasuscorp.orbe.prefetch;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * Brief matinal quand le téléphone charge et est idle (PR4).
 * Complète l'alarme {@link PrefetchScheduler} — {@link PrefetchService} est idempotent / jour.
 */
public final class PrefetchIdleJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        PrefetchService.run(getApplicationContext());
        PrefetchIdleScheduler.ensureScheduled(getApplicationContext());
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
