package com.peoplesserver.supportermod.core;

import java.util.List;
import java.util.UUID;

/** Result of a reconcile sweep, for logging and for the {@code /supporter reconcile} reply. */
public record ReconcileReport(long ranAt, List<UUID> expired) {

    public int expiredCount() {
        return expired.size();
    }
}
