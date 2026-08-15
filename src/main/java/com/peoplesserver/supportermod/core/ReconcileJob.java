package com.peoplesserver.supportermod.core;

import com.peoplesserver.supportermod.config.SupporterConfig;
import com.peoplesserver.supportermod.platform.PluginLog;
import com.peoplesserver.supportermod.platform.Scheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/** Schedules the nightly expiry sweep. */
public final class ReconcileJob {

    private static final long DAY_MS = 86_400_000L;

    private ReconcileJob() {}

    /**
     * Runs a sweep immediately, then every 24h at {@code reconcileHourUtc}.
     *
     * <p>The immediate run is not redundant: the server may have been down across the
     * scheduled hour, and lapsed supporters would otherwise keep their perks until the
     * following night.
     */
    public static Scheduler.Task start(
            SupporterService service,
            SupporterConfig config,
            Clock clock,
            Scheduler scheduler,
            PluginLog log) {
        try {
            ReconcileReport startup = service.reconcile();
            log.info("Startup reconcile expired " + startup.expiredCount() + " supporter(s)");
        } catch (RuntimeException e) {
            // A failed sweep must not stop the plugin loading; supporters keep their perks
            // until the next run, which is the safe direction to fail in.
            log.error("Startup reconcile failed", e);
        }

        long delay = millisUntilNextRun(clock.millis(), config.reconcileHourUtc());
        log.info("Next reconcile in " + Duration.ofMillis(delay).toHours() + "h");
        return scheduler.scheduleRepeating(() -> {
            try {
                service.reconcile();
            } catch (RuntimeException e) {
                log.error("Nightly reconcile failed", e);
            }
        }, delay, DAY_MS);
    }

    /** Milliseconds from {@code nowMs} until the next occurrence of {@code hourUtc}. */
    static long millisUntilNextRun(long nowMs, int hourUtc) {
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC);
        ZonedDateTime next = now.truncatedTo(ChronoUnit.DAYS).withHour(hourUtc);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next).toMillis();
    }
}
