package com.peoplesserver.supportermod.core;

/**
 * Outcome of a grant attempt.
 *
 * @param record state after the grant, or null when nothing was written
 * @param daysAdded days actually applied; zero for ignored duplicates and queued grants
 */
public record GrantResult(Outcome outcome, SupporterRecord record, int daysAdded) {

    public enum Outcome {
        /** First ever grant for this player. Phase 2's new-supporter announcement hangs off this. */
        CREATED,

        /** Existing record extended from its current expiry. */
        EXTENDED,

        /**
         * The transaction id had already been applied, so nothing was written.
         *
         * <p>Tebex retries command delivery until the server acknowledges it. Without this
         * check a single purchase can be delivered several times.
         */
        DUPLICATE_IGNORED,

        /**
         * The username could not be resolved to a UUID, so the grant was queued and will
         * apply on the player's next login.
         */
        QUEUED_PENDING
    }

    public boolean applied() {
        return outcome == Outcome.CREATED || outcome == Outcome.EXTENDED;
    }
}
