package com.revesoft.tms.security;

import com.revesoft.tms.common.BaseEntity;
import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.user.AppUser;
import com.revesoft.tms.user.UserRepository;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Role and data-scope checks used by services.
 *
 * <p>Organisation isolation is automatic (Hibernate {@code @TenantId}); this class adds the finer
 * rules: managers only see assigned properties, tenants only their own records.
 */
@Component
public class AccessGuard {

    private final UserRepository users;

    public AccessGuard(UserRepository users) {
        this.users = users;
    }

    public AuthUser user() {
        return CurrentUser.get();
    }

    public AuthUser requireStaff() {
        AuthUser u = user();
        if (!u.isStaff()) {
            throw BusinessException.forbidden("Only administrators and managers can do this");
        }
        return u;
    }

    public AuthUser requireVendor() {
        AuthUser u = user();
        if (!u.isVendor()) {
            throw BusinessException.forbidden("Only the service provider can do this");
        }
        return u;
    }

    public AuthUser requireAdmin() {
        AuthUser u = user();
        if (!u.isAdmin()) {
            throw BusinessException.forbidden("Only administrators can do this");
        }
        return u;
    }

    /** Properties the caller may access, or {@code null} meaning "all" (admins). */
    public Set<UUID> propertyScope() {
        AuthUser u = user();
        if (u.isAdmin()) {
            return null;
        }
        if (u.isManager()) {
            return users.findById(u.userId()).map(AppUser::getPropertyIds).map(Set::copyOf).orElse(Set.of());
        }
        return Set.of();
    }

    public boolean canAccessProperty(UUID propertyId) {
        Set<UUID> scope = propertyScope();
        return scope == null || scope.contains(propertyId);
    }

    public void checkProperty(UUID propertyId) {
        if (!canAccessProperty(propertyId)) {
            throw BusinessException.forbidden("You are not assigned to this property");
        }
    }

    /** Filters by property scope; {@code null} scope keeps everything. */
    public static boolean inScope(Collection<UUID> scope, UUID propertyId) {
        return scope == null || (propertyId != null && scope.contains(propertyId));
    }

    /**
     * Defence in depth on top of {@code @TenantId}: treats a record from another organisation as
     * non-existent. A null org id means the entity was created in this transaction and not flushed
     * yet (Hibernate assigns {@code org_id} on insert), so it belongs to the current organisation.
     */
    public static <T extends BaseEntity> T owned(Optional<T> entity, String what) {
        UUID org = TenantContext.current();
        return entity
                .filter(e -> TenantContext.ROOT.equals(org) || e.getOrgId() == null || org.equals(e.getOrgId()))
                .orElseThrow(() -> BusinessException.notFound(what));
    }
}
