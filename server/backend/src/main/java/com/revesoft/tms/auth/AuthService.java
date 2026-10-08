package com.revesoft.tms.auth;

import com.revesoft.tms.audit.AuditService;
import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.license.Device;
import com.revesoft.tms.license.DeviceRepository;
import com.revesoft.tms.license.LicenseCodes;
import com.revesoft.tms.license.LicenseService;
import com.revesoft.tms.license.LicenseService.DeviceInfo;
import com.revesoft.tms.license.VendorNotifier;
import com.revesoft.tms.notification.NotificationService;
import com.revesoft.tms.org.Organization;
import com.revesoft.tms.org.OrganizationRepository;
import com.revesoft.tms.push.PushService;
import com.revesoft.tms.security.AuthUser;
import com.revesoft.tms.security.CurrentUser;
import com.revesoft.tms.security.Role;
import com.revesoft.tms.security.TokenService;
import com.revesoft.tms.security.TxRunner;
import com.revesoft.tms.user.AppUser;
import com.revesoft.tms.user.PasswordPolicy;
import com.revesoft.tms.user.UserRepository;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Login, token refresh, logout, sign-up and password changes (SRS §4).
 *
 * <p>Login runs before the organisation is known, so the user lookup happens as ROOT; every change
 * after that runs inside the user's own organisation.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String INVALID = "Invalid credentials";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final OrganizationRepository orgs;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final TxRunner tx;
    private final AuditService audit;
    private final NotificationService notifications;
    private final LicenseService licenses;
    private final DeviceRepository devices;
    private final VendorNotifier vendor;
    private final PushService push;
    private final int maxFailedLogins;
    private final Duration refreshTtl;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, OrganizationRepository orgs,
                       PasswordEncoder encoder, TokenService tokens, TxRunner tx, AuditService audit,
                       NotificationService notifications, LicenseService licenses, DeviceRepository devices,
                       VendorNotifier vendor, PushService push,
                       @Value("${tms.security.max-failed-logins}") int maxFailedLogins,
                       @Value("${tms.security.refresh-token-days}") long refreshDays) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.orgs = orgs;
        this.encoder = encoder;
        this.tokens = tokens;
        this.tx = tx;
        this.audit = audit;
        this.notifications = notifications;
        this.licenses = licenses;
        this.devices = devices;
        this.vendor = vendor;
        this.push = push;
        this.maxFailedLogins = maxFailedLogins;
        this.refreshTtl = Duration.ofDays(refreshDays);
    }

    // ---------------------------------------------------------------- DTOs

    /**
     * {@code deviceKey} is a random id the app generates once per installation; it is required for
     * staff (admin app) because the licence limits the number of admin devices.
     */
    public record LoginRequest(@NotBlank String identifier, @NotBlank String password, String deviceKey,
                               String deviceName, String platform) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    /** {@code pushToken} (optional): this installation's FCM token, unregistered together with the session. */
    public record LogoutRequest(@NotBlank String refreshToken, String pushToken) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    public record ForgotPasswordRequest(@NotBlank String identifier) {
    }

    public record SignupRequest(
            @NotBlank @Size(max = 150) String organizationName,
            @NotBlank @Size(max = 150) String name,
            @NotBlank @Size(min = 3, max = 60) @Pattern(regexp = "[A-Za-z0-9._-]+",
                    message = "letters, digits, dot, dash or underscore only") String username,
            @NotBlank String password,
            @Email String email,
            @NotBlank @Pattern(regexp = "\\+?\\d{10,14}", message = "invalid mobile number") String mobile,
            String address) {
    }

    public record Me(UUID userId, String name, String username, Role role, UUID orgId, String orgName,
                     String orgStatus, String licenseState, String currency, UUID tenantId,
                     boolean mustChangePassword) {
    }

    public record TokenResponse(String accessToken, long expiresIn, String refreshToken, Me user) {
    }

    public record SignupResponse(UUID organizationId, String status, String message) {
    }

    // ---------------------------------------------------------------- Login

    public TokenResponse login(LoginRequest r) {
        AppUser found = tx.asRoot(() -> findForLogin(r.identifier()));
        if (found == null) {
            log.info("Login refused: no account matches '{}'", r.identifier().trim());
            throw unauthorized(INVALID);
        }
        UUID orgId = found.getOrgId();
        // Password check and counters run inside the user's organisation.
        LoginOutcome outcome = tx.inOrg(orgId, () -> {
            AppUser u = users.findById(found.getId()).orElseThrow();
            if (u.getStatus() == AppUser.Status.LOCKED) {
                return LoginOutcome.fail("Account locked after too many failed attempts. Contact the administrator.");
            }
            if (u.getStatus() == AppUser.Status.INACTIVE) {
                return LoginOutcome.fail("Account is inactive. Contact the administrator.");
            }
            if (u.getStatus() == AppUser.Status.BLOCKED) {
                // The reason is only shown to someone who knows the password, and wrong guesses
                // must not turn the block into an automatic lock.
                if (!encoder.matches(r.password(), u.getPasswordHash())) {
                    return LoginOutcome.fail(INVALID);
                }
                return LoginOutcome.refused(blocked(u));
            }
            if (!encoder.matches(r.password(), u.getPasswordHash())) {
                u.setFailedAttempts(u.getFailedAttempts() + 1);
                if (u.getFailedAttempts() >= maxFailedLogins) {
                    log.warn("Account '{}' locked after {} failed logins", u.getUsername(), u.getFailedAttempts());
                    u.setStatus(AppUser.Status.LOCKED);
                    audit.logAs(u.getId(), u.getName(), "Account Locked", "User", u.getUsername(), null);
                    return LoginOutcome.fail("Account locked after too many failed attempts.");
                }
                return LoginOutcome.fail(INVALID + " (" + (maxFailedLogins - u.getFailedAttempts()) + " attempts left)");
            }
            u.setFailedAttempts(0);
            Device device = licenses.registerDevice(u, new DeviceInfo(r.deviceKey(), r.deviceName(), r.platform()));
            if (device != null && device.getStatus() != Device.Status.APPROVED) {
                // Keep the pending device record (commit), but refuse the sign-in.
                return LoginOutcome.refused(LicenseService.deviceRefusal(device));
            }
            audit.logAs(u.getId(), u.getName(), "Login", "User", u.getUsername(), null);
            return LoginOutcome.ok(issue(u, device == null ? null : device.getId()));
        });
        if (outcome.refusal() != null) {
            log.info("Login refused for '{}' ({}, org {}): {}{}", found.getUsername(), found.getRole(), orgId,
                    outcome.refusal().getCode() == null ? "" : outcome.refusal().getCode() + " ",
                    outcome.refusal().getMessage());
            throw outcome.refusal();
        }
        log.debug("Login: '{}' ({}, org {}) from {} / {}", found.getUsername(), found.getRole(), orgId,
                r.platform(), r.deviceName());
        return outcome.tokens();
    }

    private record LoginOutcome(TokenResponse tokens, BusinessException refusal) {
        static LoginOutcome ok(TokenResponse t) {
            return new LoginOutcome(t, null);
        }

        static LoginOutcome fail(String message) {
            return new LoginOutcome(null, unauthorized(message));
        }

        static LoginOutcome refused(BusinessException e) {
            return new LoginOutcome(null, e);
        }
    }

    /** Username first; email or mobile only when exactly one account matches. */
    private AppUser findForLogin(String identifier) {
        String id = identifier.trim();
        var byUsername = users.findByUsernameIgnoreCase(id);
        if (byUsername.isPresent()) {
            return byUsername.get();
        }
        List<AppUser> matches = new ArrayList<>(id.contains("@") ? users.findByEmailIgnoreCase(id) : users.findByMobile(id));
        if (matches.size() > 1) {
            throw unauthorized("Several accounts use this " + (id.contains("@") ? "email" : "mobile")
                    + ". Please log in with your username.");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    // ---------------------------------------------------------------- Refresh / logout

    public TokenResponse refresh(RefreshRequest r) {
        String hash = sha256(r.refreshToken());
        RefreshToken token = tx.asRoot(() -> refreshTokens.findByTokenHash(hash).orElse(null));
        if (token == null || !token.isUsable(Instant.now())) {
            log.debug("Refresh refused: token {}", token == null ? "unknown" : "expired or already used");
            throw unauthorized("Session expired. Please log in again.");
        }
        AppUser user = tx.asRoot(() -> users.findById(token.getUserId()).orElse(null));
        if (user != null && user.getStatus() == AppUser.Status.BLOCKED) {
            log.debug("Refresh refused for '{}': blocked", user.getUsername());
            throw blocked(user);
        }
        if (user == null || user.getStatus() != AppUser.Status.ACTIVE) {
            log.debug("Refresh refused for user {}: {}", token.getUserId(), user == null ? "deleted" : user.getStatus());
            throw unauthorized("Account is not active");
        }
        if (token.getDeviceId() != null) {
            Device.Status deviceStatus = tx.asRoot(() -> devices.findById(token.getDeviceId())
                    .map(Device::getStatus).orElse(null));
            if (deviceStatus != Device.Status.APPROVED) {
                log.debug("Refresh refused for '{}': device {} is {}", user.getUsername(), token.getDeviceId(),
                        deviceStatus);
                throw new BusinessException(HttpStatus.FORBIDDEN, LicenseCodes.DEVICE_BLOCKED,
                        "This device is no longer allowed to use the account.");
            }
        }
        return tx.inOrg(user.getOrgId(), () -> {
            RefreshToken current = refreshTokens.findById(token.getId()).orElseThrow();
            if (!current.isUsable(Instant.now())) {
                throw unauthorized("Session expired. Please log in again.");
            }
            current.setRevokedAt(Instant.now()); // rotation: each refresh token works once
            log.debug("Session refreshed for '{}'", user.getUsername());
            if (current.getDeviceId() != null) {
                devices.findById(current.getDeviceId()).ifPresent(d -> d.setLastSeenAt(Instant.now()));
            }
            return issue(users.findById(user.getId()).orElseThrow(), current.getDeviceId());
        });
    }

    public void logout(LogoutRequest r) {
        String hash = sha256(r.refreshToken());
        AppUser user = tx.asRoot(() -> {
            RefreshToken t = refreshTokens.findByTokenHash(hash).orElse(null);
            if (t == null) {
                return null;
            }
            t.setRevokedAt(Instant.now());
            return users.findById(t.getUserId()).orElse(null);
        });
        log.debug("Logout: {}", user == null ? "unknown session" : "'" + user.getUsername() + "'");
        if (user != null && r.pushToken() != null) {
            push.unregisterForUser(user.getOrgId(), user.getId(), r.pushToken());
        }
    }

    /** Must be called inside the user's organisation transaction. */
    private TokenResponse issue(AppUser u, UUID deviceId) {
        AuthUser principal = new AuthUser(u.getId(), u.getOrgId(), u.getRole(), u.getName(), u.getTenantId(),
                deviceId);
        String access = tokens.issueAccessToken(principal);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        RefreshToken t = new RefreshToken();
        t.setUserId(u.getId());
        t.setDeviceId(deviceId);
        t.setTokenHash(sha256(refresh));
        t.setExpiresAt(Instant.now().plus(refreshTtl));
        refreshTokens.save(t);
        return new TokenResponse(access, tokens.accessTtl().toSeconds(), refresh, me(u));
    }

    private Me me(AppUser u) {
        Organization org = orgs.findById(u.getOrgId()).orElseThrow();
        return new Me(u.getId(), u.getName(), u.getUsername(), u.getRole(), org.getId(), org.getName(),
                org.getStatus().name(), licenses.stateOf(org).name(), org.getCurrency(), u.getTenantId(),
                u.isMustChangePassword());
    }

    // ---------------------------------------------------------------- Authenticated user

    public Me currentUser() {
        AuthUser au = CurrentUser.get();
        return tx.inOrg(au.orgId(), () -> me(users.findById(au.userId()).orElseThrow()));
    }

    public void changePassword(ChangePasswordRequest r) {
        AuthUser au = CurrentUser.get();
        tx.inOrg(au.orgId(), () -> {
            AppUser u = users.findById(au.userId()).orElseThrow();
            if (!encoder.matches(r.currentPassword(), u.getPasswordHash())) {
                throw new BusinessException("Current password is incorrect");
            }
            if (r.currentPassword().equals(r.newPassword())) {
                throw new BusinessException("New password must be different");
            }
            PasswordPolicy.validate(r.newPassword());
            u.setPasswordHash(encoder.encode(r.newPassword()));
            u.setMustChangePassword(false);
            // Sign out other devices.
            int revoked = refreshTokens.revokeAllForUser(u.getId(), Instant.now());
            log.info("Password changed by '{}'; {} session(s) signed out", u.getUsername(), revoked);
            audit.log("Password Changed", "User", u.getUsername(), null);
            return null;
        });
    }

    /** Raises a reset request to the organisation's admins. Never reveals whether the account exists. */
    public void forgotPassword(ForgotPasswordRequest r) {
        AppUser u;
        try {
            u = tx.asRoot(() -> findForLogin(r.identifier()));
        } catch (BusinessException ambiguous) {
            return;
        }
        if (u == null) {
            log.debug("Password reset requested for unknown account '{}'", r.identifier().trim());
            return;
        }
        log.info("Password reset requested for '{}'", u.getUsername());
        tx.inOrg(u.getOrgId(), () -> {
            notifications.notifyStaff(null, "Password reset requested",
                    u.getName() + " (" + u.getUsername() + ") requested a password reset.", null);
            audit.logAs(u.getId(), u.getName(), "Password Reset Requested", "User", u.getUsername(), null);
            return null;
        });
    }

    // ---------------------------------------------------------------- Sign-up

    /**
     * A landlord registers their organisation. It starts as PENDING; the vendor approves it
     * (licensing, milestone 2).
     */
    public SignupResponse signup(SignupRequest r) {
        PasswordPolicy.validate(r.password());
        String username = r.username().trim();
        Organization org = tx.asRoot(() -> {
            if (users.existsByUsernameIgnoreCase(username)) {
                throw BusinessException.conflict("Username \"" + username + "\" is already taken");
            }
            Organization o = new Organization();
            o.setName(r.organizationName().trim());
            o.setContactName(r.name().trim());
            o.setPhone(r.mobile());
            o.setEmail(r.email());
            o.setAddress(r.address());
            o.setStatus(Organization.Status.PENDING);
            return orgs.save(o);
        });
        tx.inOrg(org.getId(), () -> {
            AppUser admin = new AppUser();
            admin.setName(r.name().trim());
            admin.setUsername(username);
            admin.setEmail(r.email());
            admin.setMobile(r.mobile());
            admin.setRole(Role.ADMIN);
            admin.setPasswordHash(encoder.encode(r.password()));
            users.save(admin);
            audit.logAs(admin.getId(), admin.getName(), "Organisation Registered", "Organization",
                    org.getId().toString(), org.getName());
            licenses.recordEvent(org.getId(), "Registered", r.name().trim() + ", " + r.mobile(), null, null,
                    admin.getName());
            return null;
        });
        vendor.notifyVendors("New registration", org.getName() + " (" + r.name().trim() + ", " + r.mobile()
                + ") is waiting for approval.", "signup:" + org.getId());
        log.info("Sign-up: organisation '{}' ({}) by '{}', waiting for approval", org.getName(), org.getId(), username);
        return new SignupResponse(org.getId(), org.getStatus().name(),
                "Registration received. Your account will be activated after approval.");
    }

    // ---------------------------------------------------------------- Helpers

    private static BusinessException unauthorized(String message) {
        return new BusinessException(HttpStatus.UNAUTHORIZED, message);
    }

    /** Also used by {@link AccountStatusFilter} for users blocked while signed in. */
    public static BusinessException blocked(AppUser u) {
        String by = u.isBlockedByVendor() ? "the service provider" : "your administrator";
        String reason = u.getBlockedReason() == null ? "" : " Reason: " + u.getBlockedReason();
        return new BusinessException(HttpStatus.FORBIDDEN, LicenseCodes.ACCOUNT_BLOCKED,
                "Your account has been blocked by " + by + "." + reason);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
