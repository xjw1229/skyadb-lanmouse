package com.server.skyadb.lanmouse;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

public final class CoreAutostartJobService extends JobService {
    private static final String TAG = "SkyADB-AutostartJob";
    private static final int JOB_ID = 19_870;
    private static final long RETRY_DELAY_MS = 60_000L;

    private volatile Thread worker;

    static void scheduleNow(Context context, String reason) {
        schedule(context, 0L, reason);
    }

    static void scheduleRetry(Context context, String reason) {
        schedule(context, RETRY_DELAY_MS, reason);
    }

    private static void schedule(Context context, long delayMs, String reason) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            return;
        }
        ComponentName component = new ComponentName(context, CoreAutostartJobService.class);
        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, component)
            .setPersisted(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        if (delayMs <= 0L) {
            builder.setOverrideDeadline(0L);
        } else {
            builder.setMinimumLatency(delayMs);
            builder.setOverrideDeadline(delayMs + 30_000L);
        }
        int result = scheduler.schedule(builder.build());
        Log.i(TAG, "scheduled autostart job, reason=" + reason + ", result=" + result);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        worker = new Thread(() -> {
            boolean ready = false;
            try {
                ImeRestoreHelper.restorePreviousIme();
                for (int attempt = 0; attempt < 5 && !Thread.currentThread().isInterrupted(); attempt++) {
                    ready = CoreAutostart.isPortListening() || CoreAutostart.startIfEnabled(this);
                    if (ready || !CoreAutostart.isEnabled(this)) {
                        break;
                    }
                    Thread.sleep(2_000L + attempt * 1_000L);
                }
                if (!ready && CoreAutostart.isEnabled(this)) {
                    scheduleRetry(this, "not-ready");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (Throwable error) {
                Log.w(TAG, "autostart job failed: " + error.getMessage(), error);
                if (CoreAutostart.isEnabled(this)) {
                    scheduleRetry(this, "error");
                }
            } finally {
                jobFinished(params, !ready && CoreAutostart.isEnabled(this));
            }
        }, "skyadb-autostart-job");
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
        return CoreAutostart.isEnabled(this);
    }
}
