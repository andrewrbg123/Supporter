package com.peoplesserver.supportermod.platform;

/**
 * Port for scheduling repeating work.
 *
 * <p>Phase 0 has no question covering the server's scheduler API — that gap is flagged in
 * PLAN.md. Until it is answered the reconcile job runs on this port, backed by a plain
 * {@code ScheduledExecutorService} if the server turns out not to expose one.
 */
public interface Scheduler {

    /** A scheduled task that can be cancelled at shutdown. */
    interface Task {
        void cancel();
    }

    /** Runs {@code job} after {@code initialDelayMs}, then every {@code periodMs}. */
    Task scheduleRepeating(Runnable job, long initialDelayMs, long periodMs);
}
