package com.revesoft.tms.security;

public enum Role {
    /** The software vendor (you): manages organisations and licenses. */
    VENDOR,
    /** Landlord / organisation owner: full access within the organisation. */
    ADMIN,
    /** Property manager: limited to assigned properties. */
    MANAGER,
    /** Tenant portal user: own records only. */
    TENANT;

    public boolean isStaff() {
        return this == ADMIN || this == MANAGER;
    }
}
