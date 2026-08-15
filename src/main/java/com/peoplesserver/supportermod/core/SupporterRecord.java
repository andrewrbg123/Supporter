package com.peoplesserver.supportermod.core;

import java.util.UUID;

/**
 * One row of the {@code supporters} table.
 *
 * <p>All timestamps are epoch milliseconds UTC. There is no local time anywhere in the
 * entitlement path — a server timezone change must never move somebody's expiry.
 *
 * @param totalDays authoritative tenure. The spec asks for a {@code total_months} column;
 *     it is derived from this instead (see {@link #totalMonths()}) so that partial grants
 *     accumulate correctly rather than rounding away to nothing.
 */
public record SupporterRecord(
        UUID uuid,
        String username,
        boolean active,
        long firstGrantedAt,
        long lastGrantedAt,
        long expiresAt,
        long graceUntil,
        int totalDays,
        String source) {

    static final long DAY_MS = 86_400_000L;
    private static final int DAYS_PER_MONTH = 30;

    /**
     * Tenure in whole months, derived from {@link #totalDays()}.
     *
     * <p>Deriving rather than incrementing matters: two 15-day grants are one month of
     * tenure, but an incrementing counter using integer division would score them as zero.
     * This drives the {@code /supporters} leaderboard order and, in Phase 5, token grants.
     */
    public int totalMonths() {
        return totalDays / DAYS_PER_MONTH;
    }

    /** Status at the given instant. Pure — no clock access, so it is trivially testable. */
    public SupporterStatus statusAt(long nowMs) {
        if (!active) {
            return SupporterStatus.EXPIRED;
        }
        if (nowMs < expiresAt) {
            return SupporterStatus.ACTIVE;
        }
        if (nowMs < graceUntil) {
            return SupporterStatus.GRACE;
        }
        return SupporterStatus.EXPIRED;
    }

    /** Whether perks should be delivered at the given instant. */
    public boolean entitledAt(long nowMs) {
        return statusAt(nowMs).entitled();
    }

    /** Whole days of paid time left, rounded up; zero once expired. */
    public long daysRemainingAt(long nowMs) {
        long remaining = expiresAt - nowMs;
        return remaining <= 0 ? 0L : (long) Math.ceil(remaining / (double) DAY_MS);
    }

    /** Copy with entitlement ended immediately. Tenure is history and is preserved. */
    public SupporterRecord deactivatedAt(long nowMs) {
        return new SupporterRecord(
                uuid, username, false, firstGrantedAt, lastGrantedAt,
                Math.min(expiresAt, nowMs), Math.min(graceUntil, nowMs), totalDays, source);
    }

    /** Copy with a refreshed username, for players who renamed since their last login. */
    public SupporterRecord withUsername(String newUsername) {
        return new SupporterRecord(
                uuid, newUsername, active, firstGrantedAt, lastGrantedAt,
                expiresAt, graceUntil, totalDays, source);
    }
}
