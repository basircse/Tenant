package com.revesoft.tms.license;

import static com.revesoft.tms.license.LicenseCodes.DEVICE_BLOCKED;
import static com.revesoft.tms.license.LicenseCodes.DEVICE_PENDING;
import static com.revesoft.tms.license.LicenseCodes.DEVICE_REQUIRED;
import static com.revesoft.tms.license.LicenseCodes.LICENSE_EXPIRED;
import static com.revesoft.tms.license.LicenseCodes.LICENSE_PENDING;
import static com.revesoft.tms.license.LicenseCodes.LICENSE_READ_ONLY;
import static com.revesoft.tms.license.LicenseCodes.LICENSE_REJECTED;
import static com.revesoft.tms.license.LicenseCodes.LICENSE_SUSPENDED;
import static com.revesoft.tms.license.LicenseCodes.UNIT_LIMIT;

import com.revesoft.tms.common.BusinessClock;
import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.notification.NotificationService;
import com.revesoft.tms.org.Organization;
import com.revesoft.tms.org.OrganizationRepository;
import com.revesoft.tms.property.Unit;
import com.revesoft.tms.property.UnitRepository;
import com.revesoft.tms.security.AuthUser;
import com.revesoft.tms.security.CurrentUser;
import com.revesoft.tms.security.TenantContext;
import com.revesoft.tms.security.TxRunner;
import com.revesoft.tms.user.AppUser;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Licence rules for landlord organisations: approval state, expiry with a read-only grace period,
 * admin-app device limits and unit limits.
 */
@Service
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    /** Reminder thresholds (days before expiry). */
    private static final int[] REMINDER_DAYS = {1, 7, 15};

    private final OrganizationRepository orgs;
    private final DeviceRepository devices;
    private final UnitRepository units;
    private final LicenseEventRepository events;
    private final NotificationService notifications;
    private final VendorNotifier vendor;
    private final TxRunner tx;
    private final BusinessClock clock;
    private final int graceDays;

    public LicenseService(OrganizationRepository orgs, DeviceRepository devices, UnitRepository units,
                          LicenseEventRepository events, NotificationService notifications, VendorNotifier vendor,
                          TxRunner tx, BusinessClock clock, @Value("${tms.license.grace-days:7}") int graceDays) {
        this.orgs = orgs;
        this.devices = devices;
        this.units = units;
        this.events = events;
        this.notifications = notifications;
        this.vendor = vendor;
        this.tx = tx;
        this.clock = clock;
        this.graceDays = graceDays;
    }

    // ---------------------------------------------------------------- State

    public LicenseState stateOf(Organization o) {
        if (o.isVendor()) {
            return LicenseState.ACTIVE;
        }
        return switch (o.getStatus()) {
            case PENDING -> LicenseState.PENDING;
            case REJECTED -> LicenseState.REJECTED;
            case SUSPENDED -> LicenseState.SUSPENDED;
            case ACTIVE -> {
                Instant expires = o.getLicenseExpiresAt();
                Instant now = clock.now();
                if (expires == null || !now.isAfter(expires)) {
                    yield LicenseState.ACTIVE;
                }
                yield now.isAfter(expires.plus(Duration.ofDays(graceDays))) ? LicenseState.EXPIRED : LicenseState.GRACE;
            }
        };
    }

    public LocalDate toDate(Instant instant) {
        return instant == null ? null : LocalDate.ofInstant(instant, clock.zone());
    }

    /** A licence "expires on" a date means it is valid until the end of that day. */
    public Instant endOfDay(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(clock.zone()).toInstant().minusSeconds(1);
    }

    public Long daysLeft(Organization o) {
        return o.getLicenseExpiresAt() == null ? null
                : ChronoUnit.DAYS.between(clock.today(), toDate(o.getLicenseExpiresAt()));
    }

    public LocalDate graceEndsOn(Organization o) {
        return o.getLicenseExpiresAt() == null ? null : toDate(o.getLicenseExpiresAt()).plusDays(graceDays);
    }

    // ---------------------------------------------------------------- Request check (LicenseFilter)

    /**
     * Throws when the caller's organisation may not use the API right now. Reads are allowed during
     * the grace period, writes are not. Staff tokens must belong to an approved device.
     */
    public void checkRequest(AuthUser user, boolean write) {
        if (user.isVendor()) {
            return;
        }
        Organization o = tx.asRoot(() -> orgs.findById(user.orgId()).orElse(null));
        if (o == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, LICENSE_SUSPENDED, "Organisation not found");
        }
        switch (stateOf(o)) {
            case PENDING -> throw blocked(LICENSE_PENDING,
                    "Your account is waiting for approval. You will be notified once it is activated.");
            case REJECTED -> throw blocked(LICENSE_REJECTED, "Your registration was not approved. Please contact support.");
            case SUSPENDED -> throw blocked(LICENSE_SUSPENDED, "This account has been suspended. Please contact support.");
            case EXPIRED -> throw blocked(LICENSE_EXPIRED,
                    "The subscription expired on " + DATE.format(toDate(o.getLicenseExpiresAt()))
                            + ". Please renew to continue.");
            case GRACE -> {
                if (write) {
                    throw blocked(LICENSE_READ_ONLY, "The subscription expired on "
                            + DATE.format(toDate(o.getLicenseExpiresAt())) + ". The app is read-only until "
                            + DATE.format(graceEndsOn(o)) + ". Please renew to make changes.");
                }
            }
            case ACTIVE -> {
            }
        }
        if (user.isStaff() && user.deviceId() != null) {
            Device.Status status = tx.asRoot(() -> devices.findById(user.deviceId()).map(Device::getStatus).orElse(null));
            if (status != Device.Status.APPROVED) {
                throw blocked(DEVICE_BLOCKED, "This device is no longer allowed to use the account.");
            }
        }
    }

    private static BusinessException blocked(String code, String message) {
        return new BusinessException(HttpStatus.FORBIDDEN, code, message);
    }

    // ---------------------------------------------------------------- Devices (login)

    public record DeviceInfo(String deviceKey, String deviceName, String platform) {
    }

    /**
     * Registers (or refreshes) the device a user signs in from. Must run inside the user's
     * organisation transaction. Returns the device (possibly PENDING / BLOCKED — the caller decides)
     * or null for vendor users and tenants without a device id.
     */
    @Transactional
    public Device registerDevice(AppUser user, DeviceInfo info) {
        if (user.getRole() == com.revesoft.tms.security.Role.VENDOR) {
            return null;
        }
        boolean staff = user.getRole().isStaff();
        if (info == null || info.deviceKey() == null || info.deviceKey().isBlank()) {
            if (staff) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, DEVICE_REQUIRED,
                        "This app version does not send a device id. Please update the app.");
            }
            return null;
        }
        Device.App app = staff ? Device.App.ADMIN : Device.App.TENANT;
        String key = info.deviceKey().trim();
        Organization org = orgs.findById(TenantContext.requireOrg()).orElseThrow();
        Device d = devices.findByAppAndDeviceKey(app, key).orElse(null);
        boolean isNew = d == null;
        if (isNew) {
            d = new Device();
            d.setDeviceKey(key);
            d.setApp(app);
            d.setStatus(app == Device.App.TENANT || hasDeviceSlot(org) ? Device.Status.APPROVED : Device.Status.PENDING);
        } else if (d.getStatus() == Device.Status.PENDING && hasDeviceSlot(org)) {
            d.setStatus(Device.Status.APPROVED); // a slot was freed meanwhile
        }
        d.setName(info.deviceName());
        d.setPlatform(info.platform());
        d.setLastUserId(user.getId());
        d.setLastSeenAt(clock.now());
        devices.save(d);
        if (isNew && d.getStatus() == Device.Status.PENDING) {
            String label = (d.getName() == null ? "A new device" : d.getName()) + " (" + user.getName() + ")";
            notifications.notifyAdmins("New device waiting",
                    label + " tried to sign in, but the device limit (" + org.getMaxDevices()
                            + ") is reached. Remove an unused device in Settings → Devices.", null);
            vendor.notifyVendors("Device approval requested", org.getName() + ": " + label
                    + " exceeds the device limit.", "device:" + d.getId());
            recordEvent(org.getId(), "Device Pending", label, null, null, user.getName());
        }
        return d;
    }

    public static BusinessException deviceRefusal(Device d) {
        return d.getStatus() == Device.Status.BLOCKED
                ? new BusinessException(HttpStatus.FORBIDDEN, DEVICE_BLOCKED,
                "This device has been blocked for this account.")
                : new BusinessException(HttpStatus.FORBIDDEN, DEVICE_PENDING,
                "This device is waiting for approval because the account's device limit is reached. "
                        + "Ask your administrator to remove an unused device.");
    }

    private boolean hasDeviceSlot(Organization org) {
        return org.getMaxDevices() == null
                || devices.countByAppAndStatus(Device.App.ADMIN, Device.Status.APPROVED) < org.getMaxDevices();
    }

    // ---------------------------------------------------------------- Units

    /** Throws when adding (or re-activating) one more unit would exceed the plan's unit limit. */
    @Transactional(readOnly = true)
    public void checkUnitCapacity() {
        Organization org = orgs.findById(TenantContext.requireOrg()).orElseThrow();
        if (org.getMaxUnits() == null) {
            return;
        }
        long used = units.countByStatusNot(Unit.Status.INACTIVE);
        if (used >= org.getMaxUnits()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, UNIT_LIMIT,
                    "Your plan allows " + org.getMaxUnits() + " units. Contact support to upgrade.");
        }
    }

    // ---------------------------------------------------------------- Landlord view

    public record LicenseStatus(LicenseState state, String orgStatus, String plan, LocalDate expiresOn, Long daysLeft,
                                LocalDate graceEndsOn, boolean readOnly, Integer maxUnits, long unitsUsed,
                                Integer maxDevices, long devicesUsed, String message) {
    }

    @Transactional(readOnly = true)
    public LicenseStatus status() {
        Organization o = orgs.findById(TenantContext.requireOrg()).orElseThrow();
        LicenseState state = stateOf(o);
        String message = switch (state) {
            case PENDING -> "Waiting for approval.";
            case REJECTED -> "Registration was not approved.";
            case SUSPENDED -> "Account suspended. Please contact support.";
            case GRACE -> "Subscription expired. Read-only until " + DATE.format(graceEndsOn(o)) + ".";
            case EXPIRED -> "Subscription expired. Please renew.";
            case ACTIVE -> o.getLicenseExpiresAt() == null ? "Active."
                    : "Active until " + DATE.format(toDate(o.getLicenseExpiresAt())) + ".";
        };
        return new LicenseStatus(state, o.getStatus().name(), o.getPlan(), toDate(o.getLicenseExpiresAt()),
                daysLeft(o), graceEndsOn(o), state == LicenseState.GRACE, o.getMaxUnits(),
                units.countByStatusNot(Unit.Status.INACTIVE), o.getMaxDevices(),
                devices.countByAppAndStatus(Device.App.ADMIN, Device.Status.APPROVED), message);
    }

    /** The landlord asks the vendor to renew or upgrade. */
    public void requestRenewal(String message) {
        AuthUser me = CurrentUser.get();
        Organization o = tx.inOrg(me.orgId(), () -> {
            Organization org = orgs.findById(me.orgId()).orElseThrow();
            recordEvent(org.getId(), "Renewal Requested", message, null, null, me.name());
            return org;
        });
        vendor.notifyVendors("Renewal requested",
                o.getName() + " (" + me.name() + "): " + (message == null || message.isBlank() ? "Please renew." : message),
                null);
    }

    // ---------------------------------------------------------------- Devices (landlord admin)

    public record DeviceView(UUID id, Device.App app, String name, String platform, Device.Status status,
                             UUID lastUserId, Instant lastSeenAt, Instant registeredAt) {
        public static DeviceView of(Device d) {
            return new DeviceView(d.getId(), d.getApp(), d.getName(), d.getPlatform(), d.getStatus(),
                    d.getLastUserId(), d.getLastSeenAt(), d.getCreatedAt());
        }
    }

    @Transactional(readOnly = true)
    public List<DeviceView> myDevices() {
        return devices.findByAppOrderByLastSeenAtDesc(Device.App.ADMIN).stream().map(DeviceView::of).toList();
    }

    /** Removes a device (signs it out) to free a slot. */
    @Transactional
    public void removeDevice(UUID id) {
        Device d = com.revesoft.tms.security.AccessGuard.owned(devices.findById(id), "Device");
        AuthUser me = CurrentUser.get();
        if (d.getId().equals(me.deviceId())) {
            throw new BusinessException("You cannot remove the device you are using");
        }
        if (d.getStatus() == Device.Status.BLOCKED) {
            throw new BusinessException("Blocked devices can only be changed by support");
        }
        devices.delete(d);
        recordEvent(me.orgId(), "Device Removed", d.getName() == null ? d.getDeviceKey() : d.getName(), null, null,
                me.name());
    }

    // ---------------------------------------------------------------- Reminders (daily job)

    /** Expiry reminders for the organisation's admins (and the vendor). Runs inside the org context. */
    @Transactional
    public void sendReminders(Organization o) {
        if (o.isVendor() || o.getLicenseExpiresAt() == null) {
            return;
        }
        LicenseState state = stateOf(o);
        String expiry = DATE.format(toDate(o.getLicenseExpiresAt()));
        String keyBase = "license:" + toDate(o.getLicenseExpiresAt()) + ":";
        if (state == LicenseState.ACTIVE) {
            long left = daysLeft(o);
            for (int threshold : REMINDER_DAYS) {
                if (left <= threshold) {
                    notifications.notifyAdmins("Subscription expiring",
                            "Your subscription ends on " + expiry + " (" + left + " day" + (left == 1 ? "" : "s")
                                    + " left). Please renew to avoid interruption.", keyBase + threshold);
                    if (threshold == 7) {
                        vendor.notifyVendors("Licence expiring", o.getName() + " expires on " + expiry + ".",
                                keyBase + o.getId());
                    }
                    break;
                }
            }
        } else if (state == LicenseState.GRACE) {
            notifications.notifyAdmins("Subscription expired",
                    "Your subscription expired on " + expiry + ". The app is read-only until "
                            + DATE.format(graceEndsOn(o)) + ".", keyBase + "grace");
        } else if (state == LicenseState.EXPIRED) {
            notifications.notifyAdmins("Subscription expired",
                    "Your subscription expired on " + expiry + ". Please renew to continue.", keyBase + "expired");
        }
    }

    // ---------------------------------------------------------------- History

    /** License events are not org-scoped, so they can be written from any context. */
    @Transactional
    public void recordEvent(UUID orgId, String action, String details, Instant before, Instant after, String actor) {
        log.info("Licence: {} for org {} by {}{}", action, orgId, actor, details == null ? "" : " - " + details);
        LicenseEvent e = new LicenseEvent();
        e.setOrgId(orgId);
        e.setAction(action);
        e.setDetails(details);
        e.setExpiresBefore(before);
        e.setExpiresAfter(after);
        e.setActorName(actor);
        events.save(e);
    }
}
