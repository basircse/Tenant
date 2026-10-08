package com.revesoft.tms.security;

import com.revesoft.tms.common.BusinessException;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Which organisation's data the current thread may touch.
 *
 * <p>Normally this is the organisation in the caller's access token. Server code that runs without a
 * caller (login, sign-up, jobs) sets it explicitly with {@link #callAs}. Hibernate reads it when a
 * session opens (see {@link OrgTenantResolver}), so it must be set <em>before</em> the transaction
 * starts — use {@link TxRunner} for that.
 */
public final class TenantContext {

    /** Sees all organisations (login lookups, vendor tools, jobs that iterate organisations). */
    public static final UUID ROOT = new UUID(0, 0);

    /** Matches no organisation: unauthenticated code sees nothing. */
    public static final UUID NONE = new UUID(0, 1);

    private static final ThreadLocal<UUID> OVERRIDE = new ThreadLocal<>();

    private TenantContext() {
    }

    public static UUID current() {
        UUID override = OVERRIDE.get();
        if (override != null) {
            return override;
        }
        AuthUser user = CurrentUser.orNull();
        return user != null ? user.orgId() : NONE;
    }

    /** The current organisation; fails for ROOT/NONE. */
    public static UUID requireOrg() {
        UUID org = current();
        if (ROOT.equals(org) || NONE.equals(org)) {
            throw new BusinessException("No organisation in context");
        }
        return org;
    }

    public static <T> T callAs(UUID orgId, Supplier<T> work) {
        UUID previous = OVERRIDE.get();
        OVERRIDE.set(orgId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                OVERRIDE.remove();
            } else {
                OVERRIDE.set(previous);
            }
        }
    }
}
