package com.peoplesserver.supportermod.platform.hytale;

import com.hypixel.hytale.server.core.task.TaskRegistry;
import com.peoplesserver.supportermod.platform.Scheduler;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link Scheduler} backed by a single daemon thread.
 *
 * <p>Phase 0b confirmed the server exposes nothing that <em>creates</em> scheduled work. The
 * only task API is a registry for tracking futures you made yourself:
 *
 * <pre>
 * public TaskRegistration TaskRegistry.registerTask(ScheduledFuture&lt;Void&gt;)
 * </pre>
 *
 * <p>We use both: our own executor to run the job, and the registry so the host also cancels it
 * during shutdown. That belt-and-braces is deliberate — FactionMod v1.19.8 shipped a bug where
 * teardown was deferred to the world thread, which was already winding down, so the queued work
 * never ran and NPCs leaked into the world on every restart. A shutdown path that depends only
 * on our own code running is exactly that shape of bug.
 *
 * <p><b>This thread must not touch entities or components.</b> The reconcile job only reads and
 * writes the plugin's own SQLite database and sends chat, both of which are safe off the world
 * thread. Anything that touches the world — Phase 4's trails in particular — has to hop across
 * with {@code world.execute(...)} first.
 *
 * <p>Daemon thread on purpose: a non-daemon scheduler can hold the JVM open after shutdown if
 * cancellation is ever missed.
 */
public final class ExecutorScheduler implements Scheduler, AutoCloseable {

    private final ScheduledExecutorService executor;
    private final TaskRegistry taskRegistry;

    /** @param taskRegistry the plugin's {@code getTaskRegistry()}, or null in tests */
    public ExecutorScheduler(TaskRegistry taskRegistry) {
        this.taskRegistry = taskRegistry;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SupporterMod-Reconcile");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public Task runOnce(Runnable job, long delayMs) {
        Runnable guarded = () -> {
            try {
                job.run();
            } catch (Throwable t) {
                System.err.println("[SupporterMod] one-shot job failed: " + t);
            }
        };
        ScheduledFuture<?> future = executor.schedule(guarded, delayMs, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Task scheduleRepeating(Runnable job, long initialDelayMs, long periodMs) {
        // Swallow throwables inside the job: an uncaught exception from scheduleAtFixedRate
        // silently cancels all future runs, so one bad night would stop reconciling forever
        // with nothing in the log to say why.
        Runnable guarded = () -> {
            try {
                job.run();
            } catch (Throwable t) {
                System.err.println("[SupporterMod] scheduled job failed: " + t);
                t.printStackTrace();
            }
        };

        ScheduledFuture<?> future =
                executor.scheduleAtFixedRate(guarded, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);

        if (taskRegistry != null) {
            // Unchecked but safe: the value type is never read, only cancelled.
            taskRegistry.registerTask((ScheduledFuture<Void>) future);
        }
        return () -> future.cancel(false);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
