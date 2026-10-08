package com.revesoft.tms.license;

/** The effective licence state of an organisation at a point in time. */
public enum LicenseState {
    /** Registered, waiting for vendor approval. */
    PENDING,
    /** Registration declined by the vendor. */
    REJECTED,
    /** Blocked by the vendor. */
    SUSPENDED,
    /** Fully usable. */
    ACTIVE,
    /** Expired, inside the grace period: read-only. */
    GRACE,
    /** Expired beyond the grace period: locked until renewed. */
    EXPIRED;

    public boolean allowsReads() {
        return this == ACTIVE || this == GRACE;
    }
}
