package com.revesoft.tms.license;

import com.revesoft.tms.auth.RefreshTokenRepository;
import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.notification.NotificationService;
import com.revesoft.tms.org.Organization;
import com.revesoft.tms.org.OrganizationRepository;
import com.revesoft.tms.property.Unit;
import com.revesoft.tms.property.UnitRepository;
import com.revesoft.tms.security.CurrentUser;
import com.revesoft.tms.security.Role;
import com.revesoft.tms.security.TxRunner;
import com.revesoft.tms.user.AppUser;
import com.revesoft.tms.user.UserRepository;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The vendor console: approve landlords, manage subscriptions, limits and devices across all
 * organisations. Everything runs as ROOT because it spans organisations.
 */
@Service
public class VendorService {

    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final UnitRepository units;
    private final DeviceRepository devices;
    private final LicenseEventRepository events;
    private final RefreshTokenRepository refreshTokens;
    private final LicenseService licenses;
    private final NotificationService notifications;
    private final TxRunner tx;

    public VendorService(OrganizationRepository orgs, UserRepository users, UnitRepository units,
                         DeviceRepository devices, LicenseEventRepository events, RefreshTokenRepository refreshTokens,
                         LicenseService licenses, NotificationService notifications, TxRunner tx) {
        this.orgs = orgs;
        this.users = users;
        this.units = units;
        this.devices = devices;
        this.events = events;
        this.refreshTokens = refreshTokens;
        this.licenses = licenses;
        this.notifications = notifications;
        this.tx = tx;
    }

    // ---------------------------------------------------------------- DTOs

    public record ApproveRequest(/** Null = no expiry. */ LocalDate expiresOn, @Min(1) Integer maxUnits,
                                 @Min(1) Integer maxDevices, String plan, String note) {
    }

    public record ExtendRequest(@NotNull LocalDate expiresOn, String note) {
    }

    public record LimitsRequest(@Min(1) Integer maxUnits, @Min(1) Integer maxDevices, String plan, String note) {
    }

    public record ReasonRequest(@NotBlank String reason) {
    }

    public record NoteRequest(String note) {
    }

    public record OrgSummary(UUID id, String name, String contactName, String phone, String email,
                             Organization.Status status, LicenseState state, String plan, LocalDate expiresOn,
                             Long daysLeft, Integer maxUnits, long unitsUsed, Integer maxDevices, long devicesUsed,
                             long pendingDevices, Instant registeredAt, Instant approvedAt) {
    }

    public record EventView(String action, String details, LocalDate expiresBefore, LocalDate expiresAfter,
                            String actorName, Instant at) {
    }

    public record AdminContact(UUID id, String name, String username, String email, String mobile,
                               AppUser.Status status) {
    }

    public record DeviceAdminView(UUID id, Device.App app, String name, String platform, Device.Status status,
                                  Instant lastSeenAt, Instant registeredAt) {
        static DeviceAdminView of(Device d) {
            return new DeviceAdminView(d.getId(), d.getApp(), d.getName(), d.getPlatform(), d.getStatus(),
                    d.getLastSeenAt(), d.getCreatedAt());
        }
    }

    public record OrgDetail(OrgSummary organization, String address, String licenseNote, List<AdminContact> admins,
                            List<DeviceAdminView> devices, List<EventView> history) {
    }

    public record Summary(long pending, long active, long expiringIn30Days, long inGrace, long expired,
                          long suspended, long rejected, long pendingDevices) {
    }

    // ---------------------------------------------------------------- Queries

    public Summary summary() {
        return tx.asRoot(() -> {
            List<OrgSummary> all = summaries();
            return new Summary(
                    count(all, LicenseState.PENDING),
                    count(all, LicenseState.ACTIVE),
                    all.stream().filter(o -> o.state() == LicenseState.ACTIVE && o.daysLeft() != null
                            && o.daysLeft() <= 30).count(),
                    count(all, LicenseState.GRACE),
                    count(all, LicenseState.EXPIRED),
                    count(all, LicenseState.SUSPENDED),
                    count(all, LicenseState.REJECTED),
                    all.stream().mapToLong(OrgSummary::pendingDevices).sum());
        });
    }

    public List<OrgSummary> list(LicenseState state, String q) {
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return tx.asRoot(() -> summaries().stream()
                .filter(o -> state == null || o.state() == state)
                .filter(o -> query.isEmpty() || contains(query, o.name(), o.contactName(), o.phone(), o.email()))
                .toList());
    }

    public OrgDetail get(UUID orgId) {
        return tx.asRoot(() -> detail(load(orgId)));
    }

    // ---------------------------------------------------------------- Commands

    public OrgDetail approve(UUID orgId, ApproveRequest r) {
        return change(orgId, o -> {
            if (o.getStatus() != Organization.Status.PENDING && o.getStatus() != Organization.Status.REJECTED) {
                throw new BusinessException("Only pending or rejected registrations can be approved");
            }
            Instant before = o.getLicenseExpiresAt();
            o.setStatus(Organization.Status.ACTIVE);
            o.setApprovedAt(Instant.now());
            o.setLicenseExpiresAt(licenses.endOfDay(r.expiresOn()));
            o.setMaxUnits(r.maxUnits());
            o.setMaxDevices(r.maxDevices());
            o.setPlan(r.plan());
            o.setLicenseNote(r.note());
            event(o, "Approved", describe(o) + note(r.note()), before);
            return new Notice("Account approved", "Your account is active"
                    + (r.expiresOn() == null ? "." : " until " + LicenseService.DATE.format(r.expiresOn()) + ".")
                    + " Welcome!");
        });
    }

    public OrgDetail reject(UUID orgId, String reason) {
        return change(orgId, o -> {
            if (o.getStatus() != Organization.Status.PENDING) {
                throw new BusinessException("Only pending registrations can be rejected");
            }
            o.setStatus(Organization.Status.REJECTED);
            event(o, "Rejected", reason, o.getLicenseExpiresAt());
            return null;
        });
    }

    /** Renews / extends (or shortens) the subscription to the given end date. */
    public OrgDetail extend(UUID orgId, ExtendRequest r) {
        return change(orgId, o -> {
            if (o.getStatus() == Organization.Status.PENDING || o.getStatus() == Organization.Status.REJECTED) {
                throw new BusinessException("Approve the registration first");
            }
            Instant before = o.getLicenseExpiresAt();
            o.setLicenseExpiresAt(licenses.endOfDay(r.expiresOn()));
            event(o, "Subscription Extended", "Until " + LicenseService.DATE.format(r.expiresOn()) + note(r.note()), before);
            return new Notice("Subscription renewed",
                    "Your subscription is now valid until " + LicenseService.DATE.format(r.expiresOn()) + ". Thank you!");
        });
    }

    public OrgDetail updateLimits(UUID orgId, LimitsRequest r) {
        return change(orgId, o -> {
            o.setMaxUnits(r.maxUnits());
            o.setMaxDevices(r.maxDevices());
            o.setPlan(r.plan());
            event(o, "Limits Changed", describe(o) + note(r.note()), o.getLicenseExpiresAt());
            return new Notice("Plan updated", "Your plan was updated: " + describe(o) + ".");
        });
    }

    public OrgDetail suspend(UUID orgId, String reason) {
        return change(orgId, o -> {
            if (o.getStatus() != Organization.Status.ACTIVE) {
                throw new BusinessException("Only active accounts can be suspended");
            }
            o.setStatus(Organization.Status.SUSPENDED);
            event(o, "Suspended", reason, o.getLicenseExpiresAt());
            return null; // the landlord sees the reason when the app is blocked
        });
    }

    public OrgDetail reactivate(UUID orgId, String note) {
        return change(orgId, o -> {
            if (o.getStatus() != Organization.Status.SUSPENDED) {
                throw new BusinessException("Only suspended accounts can be reactivated");
            }
            o.setStatus(Organization.Status.ACTIVE);
            event(o, "Reactivated", note, o.getLicenseExpiresAt());
            return new Notice("Account reactivated", "Your account has been reactivated.");
        });
    }

    /** Approves a pending device even beyond the device limit (vendor override). */
    public DeviceAdminView approveDevice(UUID deviceId) {
        return setDeviceStatus(deviceId, Device.Status.APPROVED, "Device Approved");
    }

    /** Blocks a device and signs it out everywhere. */
    public DeviceAdminView blockDevice(UUID deviceId) {
        return setDeviceStatus(deviceId, Device.Status.BLOCKED, "Device Blocked");
    }

    // ---------------------------------------------------------------- Helpers

    private record Notice(String title, String body) {
    }

    private OrgDetail change(UUID orgId, Function<Organization, Notice> action) {
        Notice notice = tx.asRoot(() -> action.apply(load(orgId)));
        if (notice != null) {
            tx.inOrg(orgId, () -> {
                notifications.notifyAdmins(notice.title(), notice.body(), null);
                return null;
            });
        }
        return get(orgId);
    }

    private DeviceAdminView setDeviceStatus(UUID deviceId, Device.Status status, String action) {
        return tx.asRoot(() -> {
            Device d = devices.findById(deviceId).orElseThrow(() -> BusinessException.notFound("Device"));
            d.setStatus(status);
            if (status == Device.Status.BLOCKED) {
                refreshTokens.revokeAllForDevice(d.getId(), Instant.now());
            }
            licenses.recordEvent(d.getOrgId(), action, d.getName() == null ? d.getDeviceKey() : d.getName(),
                    null, null, CurrentUser.get().name());
            return DeviceAdminView.of(d);
        });
    }

    private Organization load(UUID orgId) {
        Organization o = orgs.findById(orgId).orElseThrow(() -> BusinessException.notFound("Organisation"));
        if (o.isVendor()) {
            throw BusinessException.notFound("Organisation");
        }
        return o;
    }

    private void event(Organization o, String action, String details, Instant before) {
        licenses.recordEvent(o.getId(), action, details, before, o.getLicenseExpiresAt(), CurrentUser.get().name());
    }

    private static String describe(Organization o) {
        return (o.getPlan() == null ? "" : o.getPlan() + ", ")
                + (o.getMaxUnits() == null ? "unlimited units" : o.getMaxUnits() + " units") + ", "
                + (o.getMaxDevices() == null ? "unlimited devices" : o.getMaxDevices() + " devices");
    }

    private static String note(String note) {
        return note == null || note.isBlank() ? "" : " — " + note.trim();
    }

    /** Must run as ROOT. */
    private List<OrgSummary> summaries() {
        Map<UUID, Long> unitCounts = units.findAll().stream()
                .filter(u -> u.getStatus() != Unit.Status.INACTIVE)
                .collect(Collectors.groupingBy(Unit::getOrgId, Collectors.counting()));
        List<Device> admin = devices.findAll().stream().filter(d -> d.getApp() == Device.App.ADMIN).toList();
        Map<UUID, Long> approved = admin.stream().filter(d -> d.getStatus() == Device.Status.APPROVED)
                .collect(Collectors.groupingBy(Device::getOrgId, Collectors.counting()));
        Map<UUID, Long> pending = admin.stream().filter(d -> d.getStatus() == Device.Status.PENDING)
                .collect(Collectors.groupingBy(Device::getOrgId, Collectors.counting()));
        return orgs.findByVendorFalseOrderByCreatedAtDesc().stream()
                .map(o -> summary(o, unitCounts.getOrDefault(o.getId(), 0L), approved.getOrDefault(o.getId(), 0L),
                        pending.getOrDefault(o.getId(), 0L)))
                .sorted(Comparator.comparing((OrgSummary s) -> s.state() != LicenseState.PENDING)
                        .thenComparing(OrgSummary::registeredAt, Comparator.reverseOrder()))
                .toList();
    }

    private OrgSummary summary(Organization o, long unitsUsed, long devicesUsed, long pendingDevices) {
        return new OrgSummary(o.getId(), o.getName(), o.getContactName(), o.getPhone(), o.getEmail(), o.getStatus(),
                licenses.stateOf(o), o.getPlan(), licenses.toDate(o.getLicenseExpiresAt()), licenses.daysLeft(o),
                o.getMaxUnits(), unitsUsed, o.getMaxDevices(), devicesUsed, pendingDevices, o.getCreatedAt(),
                o.getApprovedAt());
    }

    /** Must run as ROOT. */
    private OrgDetail detail(Organization o) {
        List<Device> orgDevices = devices.findByOrgIdOrderByLastSeenAtDesc(o.getId());
        long unitsUsed = units.findAll().stream()
                .filter(u -> u.getOrgId().equals(o.getId()) && u.getStatus() != Unit.Status.INACTIVE).count();
        long approved = orgDevices.stream()
                .filter(d -> d.getApp() == Device.App.ADMIN && d.getStatus() == Device.Status.APPROVED).count();
        long pendingCount = orgDevices.stream()
                .filter(d -> d.getApp() == Device.App.ADMIN && d.getStatus() == Device.Status.PENDING).count();
        List<AdminContact> admins = users.findByOrgIdAndRole(o.getId(), Role.ADMIN).stream()
                .map(u -> new AdminContact(u.getId(), u.getName(), u.getUsername(), u.getEmail(), u.getMobile(),
                        u.getStatus()))
                .toList();
        List<EventView> history = events.findByOrgIdOrderByCreatedAtDesc(o.getId()).stream()
                .map(e -> new EventView(e.getAction(), e.getDetails(), licenses.toDate(e.getExpiresBefore()),
                        licenses.toDate(e.getExpiresAfter()), e.getActorName(), e.getCreatedAt()))
                .toList();
        return new OrgDetail(summary(o, unitsUsed, approved, pendingCount), o.getAddress(), o.getLicenseNote(), admins,
                orgDevices.stream().map(DeviceAdminView::of).toList(), history);
    }

    private static long count(List<OrgSummary> all, LicenseState state) {
        return all.stream().filter(o -> o.state() == state).count();
    }

    private static boolean contains(String query, String... values) {
        for (String v : values) {
            if (v != null && v.toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }
}
