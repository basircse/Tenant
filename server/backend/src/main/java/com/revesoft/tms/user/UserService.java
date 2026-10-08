package com.revesoft.tms.user;

import com.revesoft.tms.audit.AuditService;
import com.revesoft.tms.auth.RefreshTokenRepository;
import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.org.Organization;
import com.revesoft.tms.org.OrganizationRepository;
import com.revesoft.tms.property.PropertyRepository;
import com.revesoft.tms.security.AccessGuard;
import com.revesoft.tms.security.AuthUser;
import com.revesoft.tms.security.Role;
import com.revesoft.tms.security.TxRunner;
import com.revesoft.tms.tenant.TenantRepository;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** User & role management (SRS §16). */
@Service
public class UserService {

    private final UserRepository repo;
    private final TenantRepository tenants;
    private final PropertyRepository properties;
    private final PasswordEncoder encoder;
    private final AccessGuard guard;
    private final AuditService audit;
    private final TxRunner tx;
    private final OrganizationRepository orgs;
    private final RefreshTokenRepository refreshTokens;

    public UserService(UserRepository repo, TenantRepository tenants, PropertyRepository properties,
                       PasswordEncoder encoder, AccessGuard guard, AuditService audit, TxRunner tx,
                       OrganizationRepository orgs, RefreshTokenRepository refreshTokens) {
        this.repo = repo;
        this.tenants = tenants;
        this.properties = properties;
        this.encoder = encoder;
        this.guard = guard;
        this.audit = audit;
        this.tx = tx;
        this.orgs = orgs;
        this.refreshTokens = refreshTokens;
    }

    public record UserView(UUID id, String name, String username, String email, String mobile, Role role,
                           AppUser.Status status, UUID tenantId, Set<UUID> propertyIds,
                           boolean mustChangePassword, Instant createdAt, String blockedReason, String blockedBy,
                           Instant blockedAt, boolean blockedByVendor) {
        public static UserView of(AppUser u) {
            return new UserView(u.getId(), u.getName(), u.getUsername(), u.getEmail(), u.getMobile(), u.getRole(),
                    u.getStatus(), u.getTenantId(), Set.copyOf(u.getPropertyIds()), u.isMustChangePassword(),
                    u.getCreatedAt(), u.getBlockedReason(), u.getBlockedBy(), u.getBlockedAt(), u.isBlockedByVendor());
        }
    }

    public record BlockRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record UserRequest(
            @NotBlank @Size(max = 150) String name,
            @NotBlank @Size(min = 3, max = 60) @Pattern(regexp = "[A-Za-z0-9._-]+",
                    message = "letters, digits, dot, dash or underscore only") String username,
            @Email String email,
            @Pattern(regexp = "^$|\\+?\\d{10,14}", message = "invalid mobile number") String mobile,
            @NotNull Role role,
            Set<UUID> propertyIds,
            UUID tenantId,
            /** Required when creating; ignored on update. */
            String password) {
    }

    public record TempPassword(String username, String temporaryPassword) {
    }

    @Transactional(readOnly = true)
    public List<UserView> list() {
        guard.requireAdmin();
        return repo.findAllByOrderByRoleAscNameAsc().stream().map(UserView::of).toList();
    }

    @Transactional(readOnly = true)
    public UserView get(UUID id) {
        guard.requireAdmin();
        return UserView.of(load(id));
    }

    @Transactional
    public UserView create(UserRequest r) {
        guard.requireAdmin();
        PasswordPolicy.validate(r.password());
        AppUser u = new AppUser();
        apply(u, r);
        u.setPasswordHash(encoder.encode(r.password()));
        u.setMustChangePassword(true);
        repo.save(u);
        audit.log("Created User", "User", u.getUsername(), u.getRole().name());
        return UserView.of(u);
    }

    @Transactional
    public UserView update(UUID id, UserRequest r) {
        AuthUser me = guard.requireAdmin();
        AppUser u = load(id);
        if (u.getId().equals(me.userId()) && r.role() != Role.ADMIN) {
            throw new BusinessException("You cannot remove your own admin role");
        }
        apply(u, r);
        audit.log("Updated User", "User", u.getUsername(), u.getRole().name());
        return UserView.of(u);
    }

    @Transactional
    public UserView setStatus(UUID id, AppUser.Status status) {
        AuthUser me = guard.requireAdmin();
        AppUser u = load(id);
        if (u.getId().equals(me.userId()) && status != AppUser.Status.ACTIVE) {
            throw new BusinessException("You cannot deactivate yourself");
        }
        if (status == AppUser.Status.BLOCKED || u.getStatus() == AppUser.Status.BLOCKED) {
            throw new BusinessException("Use block / unblock for blocked users");
        }
        u.setStatus(status);
        if (status != AppUser.Status.ACTIVE) {
            refreshTokens.revokeAllForUser(u.getId(), Instant.now());
        }
        if (status == AppUser.Status.ACTIVE) {
            u.setFailedAttempts(0);
        }
        audit.log("User " + status.name().toLowerCase(), "User", u.getUsername(), null);
        return UserView.of(u);
    }

    // ---------------------------------------------------------------- Blocking

    /**
     * Blocks a user of the admin's own organisation: they are signed out everywhere at once and
     * cannot sign in until unblocked. Other organisations' users are not found (404).
     */
    @Transactional
    public UserView block(UUID id, String reason) {
        AuthUser me = guard.requireAdmin();
        AppUser u = load(id);
        applyBlock(u, reason, me);
        return UserView.of(u);
    }

    @Transactional
    public UserView unblock(UUID id) {
        AuthUser me = guard.requireAdmin();
        AppUser u = load(id);
        if (u.isBlockedByVendor()) {
            throw BusinessException.forbidden("This user was blocked by the service provider. Contact them to unblock.");
        }
        applyUnblock(u, me);
        return UserView.of(u);
    }

    /** A user anywhere, for the vendor. */
    public record VendorUserView(UserView user, UUID orgId, String orgName) {
    }

    /** Every user of every organisation (or one), for the vendor. */
    public List<VendorUserView> listAll(UUID orgId, String q, AppUser.Status status) {
        guard.requireVendor();
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return tx.asRoot(() -> {
            Map<UUID, String> orgNames = orgs.findAll().stream()
                    .collect(Collectors.toMap(Organization::getId, Organization::getName));
            return repo.findAllByOrderByRoleAscNameAsc().stream()
                    .filter(u -> orgId == null || u.getOrgId().equals(orgId))
                    .filter(u -> status == null || u.getStatus() == status)
                    .filter(u -> query.isEmpty() || contains(query, u.getName(), u.getUsername(), u.getEmail(),
                            u.getMobile()))
                    .map(u -> new VendorUserView(UserView.of(u), u.getOrgId(), orgNames.get(u.getOrgId())))
                    .toList();
        });
    }

    /** The vendor blocks a user of any organisation; only the vendor can lift it. */
    public VendorUserView blockAny(UUID id, String reason) {
        AuthUser me = guard.requireVendor();
        return inUsersOrg(id, u -> applyBlock(u, reason, me));
    }

    /** The vendor lifts any block, including one placed by the organisation's admin. */
    public VendorUserView unblockAny(UUID id) {
        AuthUser me = guard.requireVendor();
        return inUsersOrg(id, u -> applyUnblock(u, me));
    }

    /** Runs the change inside the user's organisation, so the audit entry lands there. */
    private VendorUserView inUsersOrg(UUID id, Consumer<AppUser> change) {
        AppUser found = tx.asRoot(() -> repo.findById(id).orElseThrow(() -> BusinessException.notFound("User")));
        return tx.inOrg(found.getOrgId(), () -> {
            AppUser u = repo.findById(id).orElseThrow(() -> BusinessException.notFound("User"));
            change.accept(u);
            String orgName = orgs.findById(u.getOrgId()).map(Organization::getName).orElse(null);
            return new VendorUserView(UserView.of(u), u.getOrgId(), orgName);
        });
    }

    private void applyBlock(AppUser u, String reason, AuthUser me) {
        if (u.getId().equals(me.userId())) {
            throw new BusinessException("You cannot block yourself");
        }
        if (u.getRole() == Role.VENDOR && !me.isVendor()) {
            throw BusinessException.forbidden("You cannot block this user");
        }
        // Re-blocking would clear the vendor's flag and let the admin lift the vendor's block.
        if (u.isBlockedByVendor() && !me.isVendor()) {
            throw BusinessException.forbidden("This user was blocked by the service provider.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("Give a reason for blocking");
        }
        u.setStatus(AppUser.Status.BLOCKED);
        u.setBlockedReason(reason.trim());
        u.setBlockedBy(me.isVendor() ? "Service provider" : me.name());
        u.setBlockedAt(Instant.now());
        u.setBlockedByVendor(me.isVendor());
        refreshTokens.revokeAllForUser(u.getId(), Instant.now());
        audit.log("Blocked User", "User", u.getUsername(),
                (me.isVendor() ? "By the service provider: " : "") + reason.trim());
    }

    private void applyUnblock(AppUser u, AuthUser me) {
        if (u.getStatus() != AppUser.Status.BLOCKED) {
            throw new BusinessException("This user is not blocked");
        }
        u.setStatus(AppUser.Status.ACTIVE);
        u.setFailedAttempts(0);
        u.setBlockedReason(null);
        u.setBlockedBy(null);
        u.setBlockedAt(null);
        u.setBlockedByVendor(false);
        audit.log("Unblocked User", "User", u.getUsername(), me.isVendor() ? "By the service provider" : null);
    }

    private static boolean contains(String query, String... values) {
        for (String v : values) {
            if (v != null && v.toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }

    /** Issues a temporary password the user must change at next login. */
    @Transactional
    public TempPassword resetPassword(UUID id) {
        guard.requireAdmin();
        AppUser u = load(id);
        String temp = PasswordPolicy.temporary();
        u.setPasswordHash(encoder.encode(temp));
        u.setMustChangePassword(true);
        u.setFailedAttempts(0);
        if (u.getStatus() == AppUser.Status.LOCKED) {
            u.setStatus(AppUser.Status.ACTIVE);
        }
        audit.log("Reset Password", "User", u.getUsername(), null);
        return new TempPassword(u.getUsername(), temp);
    }

    /** Creates a portal login for a tenant record (called from tenant registration). */
    @Transactional
    public AppUser createTenantLogin(UUID tenantId, String name, String email, String mobile,
                                     String username, String password) {
        guard.requireStaff();
        PasswordPolicy.validate(password);
        AppUser u = new AppUser();
        apply(u, new UserRequest(name, username, email, mobile, Role.TENANT, null, tenantId, password));
        u.setPasswordHash(encoder.encode(password));
        u.setMustChangePassword(true);
        repo.save(u);
        audit.log("Created User", "User", u.getUsername(), "Tenant portal login");
        return u;
    }

    private AppUser load(UUID id) {
        return AccessGuard.owned(repo.findById(id), "User");
    }

    private void apply(AppUser u, UserRequest r) {
        if (r.role() == Role.VENDOR) {
            throw new BusinessException("Invalid role");
        }
        String username = r.username().trim();
        if (!username.equalsIgnoreCase(u.getUsername()) && usernameTaken(username)) {
            throw BusinessException.conflict("Username \"" + username + "\" is already taken");
        }
        u.setName(r.name().trim());
        u.setUsername(username);
        u.setEmail(blankToNull(r.email()));
        u.setMobile(blankToNull(r.mobile()));
        u.setRole(r.role());

        Set<UUID> props = new HashSet<>();
        if (r.role() == Role.MANAGER && r.propertyIds() != null) {
            for (UUID pid : r.propertyIds()) {
                AccessGuard.owned(properties.findById(pid), "Property");
                props.add(pid);
            }
        }
        u.getPropertyIds().clear();
        u.getPropertyIds().addAll(props);

        if (r.role() == Role.TENANT) {
            if (r.tenantId() == null) {
                throw new BusinessException("Select the tenant record for this login");
            }
            AccessGuard.owned(tenants.findById(r.tenantId()), "Tenant");
            repo.findByTenantId(r.tenantId()).filter(other -> !other.getId().equals(u.getId())).ifPresent(other -> {
                throw BusinessException.conflict("This tenant already has a login (" + other.getUsername() + ")");
            });
            u.setTenantId(r.tenantId());
        } else {
            u.setTenantId(null);
        }
    }

    /** Usernames are unique across all organisations, so check globally. */
    private boolean usernameTaken(String username) {
        return Boolean.TRUE.equals(tx.asRoot(() -> repo.existsByUsernameIgnoreCase(username)));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
