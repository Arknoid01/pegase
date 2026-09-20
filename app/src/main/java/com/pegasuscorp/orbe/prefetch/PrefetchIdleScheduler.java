package com.pegasuscorp.orbe.prefetch;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

import com.pegasuscorp.orbe.session.HomeLoadPolicy;

/**
 * JobScheduler — prefetch quand charge + idle (PR4).
 */
public final class PrefetchIdleScheduler {

    static final int JOB_ID = 6402;

    private PrefetchIdleScheduler() {}

    public static boolean isEnabled(Context ctx) {
        return HomeLoadPolicy.useIdlePrefetchJob(ctx);
    }

    public static void ensureScheduled(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;

        if (!isEnabled(app)) {
            cancel(app);
            return;
        }

        ComponentName cn = new ComponentName(app, PrefetchIdleJobService.class);
        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, cn)
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setPersisted(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setPeriodic(JobInfo.getMinPeriodMillis(), JobInfo.getMinFlexMillis());
        } else {
            builder.setPeriodic(4 * 60 * 60 * 1000L);
        }

        try {
            js.schedule(builder.build());
        } catch (Exception ignored) {
            // certains OEM limitent setPersisted / periodic
            try {
                js.schedule(new JobInfo.Builder(JOB_ID, cn)
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .build());
            } catch (Exception ignored2) {}
        }
    }

    public static void cancel(Context ctx) {
        if (ctx == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        JobScheduler js = (JobScheduler) ctx.getApplicationContext()
                .getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB_ID);
    }
}
