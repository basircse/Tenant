package com.revesoft.tms.security;

import java.util.UUID;

/**
 * The authenticated caller, taken from the access token (or a system job). {@code deviceId} is the
 * registered device the token was issued to (null for system jobs and vendor users).
 */
public record AuthUser(UUID userId, UUID orgId, Role role, String name, UUID tenantId, UUID deviceId) {

    public AuthUser(UUID userId, UUID orgId, Role role, String name, UUID tenantId) {
        this(userId, orgId, role, name, tenantId, null);
    }

    public boolean isVendor() {
        return role == Role.VENDOR;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public boolean isManager() {
        return role == Role.MANAGER;
    }

    public boolean isTenant() {
        return role == Role.TENANT;
    }

    public boolean isStaff() {
        return role.isStaff();
    }
}
