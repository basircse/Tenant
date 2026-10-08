package com.revesoft.tms.license;

/** Machine-readable error codes returned in the problem response's {@code code} field. */
public final class LicenseCodes {

    public static final String LICENSE_PENDING = "LICENSE_PENDING";
    public static final String LICENSE_REJECTED = "LICENSE_REJECTED";
    public static final String LICENSE_SUSPENDED = "LICENSE_SUSPENDED";
    public static final String LICENSE_EXPIRED = "LICENSE_EXPIRED";
    public static final String LICENSE_READ_ONLY = "LICENSE_READ_ONLY";
    public static final String DEVICE_REQUIRED = "DEVICE_REQUIRED";
    public static final String DEVICE_PENDING = "DEVICE_PENDING";
    public static final String DEVICE_BLOCKED = "DEVICE_BLOCKED";
    public static final String UNIT_LIMIT = "UNIT_LIMIT";
    /** The user was blocked (or deactivated or locked) while signed in, or tries to sign in. */
    public static final String ACCOUNT_BLOCKED = "ACCOUNT_BLOCKED";
    public static final String ACCOUNT_INACTIVE = "ACCOUNT_INACTIVE";

    private LicenseCodes() {
    }
}
