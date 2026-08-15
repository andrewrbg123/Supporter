package com.peoplesserver.supportermod.core;

/** Entitlement state of a player at a point in time. */
public enum SupporterStatus {

    /** Never granted supporter. */
    NONE,

    /** Paid time remaining. */
    ACTIVE,

    /** Past expiry but inside the grace window — still treated as a supporter. */
    GRACE,

    /** Past the grace window, or explicitly revoked. */
    EXPIRED;

    /** True for the two states in which perks are delivered. */
    public boolean entitled() {
        return this == ACTIVE || this == GRACE;
    }
}
