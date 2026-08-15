package com.peoplesserver.supportermod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReconcileJobTest {

    private static long at(String isoInstant) {
        return Instant.parse(isoInstant).toEpochMilli();
    }

    @Test
    @DisplayName("schedules later the same day when the hour has not passed")
    void laterToday() {
        long delay = ReconcileJob.millisUntilNextRun(at("2026-01-01T01:00:00Z"), 4);

        assertEquals(Duration.ofHours(3).toMillis(), delay);
    }

    @Test
    @DisplayName("rolls to tomorrow once the hour has passed")
    void tomorrow() {
        long delay = ReconcileJob.millisUntilNextRun(at("2026-01-01T06:00:00Z"), 4);

        assertEquals(Duration.ofHours(22).toMillis(), delay);
    }

    @Test
    @DisplayName("rolls to tomorrow when it is exactly the scheduled hour")
    void exactlyOnTheHour() {
        long delay = ReconcileJob.millisUntilNextRun(at("2026-01-01T04:00:00Z"), 4);

        assertEquals(Duration.ofHours(24).toMillis(), delay);
    }

    @Test
    @DisplayName("hour 0 is handled")
    void midnight() {
        long delay = ReconcileJob.millisUntilNextRun(at("2026-01-01T23:30:00Z"), 0);

        assertEquals(Duration.ofMinutes(30).toMillis(), delay);
    }

    @Test
    @DisplayName("UTC is used regardless of host timezone, so DST cannot shift expiry")
    void usesUtcNotLocalTime() {
        // Mid-DST-transition date in most of Europe; the answer must not move.
        long delay = ReconcileJob.millisUntilNextRun(at("2026-03-29T01:00:00Z"), 4);

        assertEquals(Duration.ofHours(3).toMillis(), delay);
    }
}
