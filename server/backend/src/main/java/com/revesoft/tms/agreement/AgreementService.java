package com.revesoft.tms.agreement;

import com.revesoft.tms.audit.AuditService;
import com.revesoft.tms.common.BusinessClock;
import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.common.CodeGenerator;
import com.revesoft.tms.common.Money;
import com.revesoft.tms.notification.NotificationService;
import com.revesoft.tms.org.OrganizationService;
import com.revesoft.tms.property.Property;
import com.revesoft.tms.property.PropertyRepository;
import com.revesoft.tms.property.Unit;
import com.revesoft.tms.property.UnitRepository;
import com.revesoft.tms.security.AccessGuard;
import com.revesoft.tms.tenant.Tenant;
import com.revesoft.tms.tenant.TenantRepository;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lease / rental agreement management (SRS §9). */
@Service
public class AgreementService {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private final AgreementRepository agreements;
    private final UnitRepository units;
    private final PropertyRepository properties;
    private final TenantRepository tenants;
    private final OrganizationService orgs;
    private final CodeGenerator codes;
    private final AccessGuard guard;
    private final AuditService audit;
    private final NotificationService notifications;
    private final BusinessClock clock;

    public AgreementService(AgreementRepository agreements, UnitRepository units, PropertyRepository properties,
                            TenantRepository tenants, OrganizationService orgs, CodeGenerator codes,
                            AccessGuard guard, AuditService audit, NotificationService notifications,
                            BusinessClock clock) {
        this.agreements = agreements;
        this.units = units;
        this.properties = properties;
        this.tenants = tenants;
        this.orgs = orgs;
        this.codes = codes;
        this.guard = guard;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- DTOs

    public record AgreementRequest(
            @NotNull UUID tenantId,
            @NotNull UUID unitId,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            /** Defaults to the unit's rent configuration when omitted. */
            @DecimalMin(value = "0.01", message = "must be greater than zero") BigDecimal monthlyRent,
            @DecimalMin("0") BigDecimal serviceCharge,
            @DecimalMin("0") BigDecimal utilityCharge,
            @DecimalMin("0") BigDecimal otherCharge,
            @DecimalMin("0") BigDecimal securityDeposit,
            @DecimalMin("0") BigDecimal advanceAmount,
            @Min(1) @Max(28) Integer dueDay,
            String documentRef,
            String terms,
            /** true = activate immediately (tenant moves in); false = save as draft. */
            boolean activate) {
    }

    public record TerminateRequest(@NotBlank String reason, LocalDate date) {
    }

    public record RenewRequest(@NotNull LocalDate newEndDate,
                               @DecimalMin(value = "0.01", message = "must be greater than zero") BigDecimal monthlyRent) {
    }

    public record AgreementView(UUID id, String code, UUID tenantId, String tenantName, UUID unitId, String unitNo,
                                UUID propertyId, String propertyName, LocalDate startDate, LocalDate endDate,
                                Long daysRemaining, BigDecimal monthlyRent, BigDecimal serviceCharge,
                                BigDecimal utilityCharge, BigDecimal otherCharge, BigDecimal totalMonthly,
                                BigDecimal securityDeposit, BigDecimal advanceAmount, int dueDay, String documentRef,
                                String terms, Agreement.Status status, UUID renewedFromId, LocalDate terminatedOn,
                                String terminationReason) {
    }

    // ---------------------------------------------------------------- Queries

    @Transactional(readOnly = true)
    public List<AgreementView> list(Agreement.Status status, UUID propertyId, UUID tenantId, String q) {
        guard.requireStaff();
        Set<UUID> scope = guard.propertyScope();
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return views(agreements.findAll().stream()
                .filter(a -> AccessGuard.inScope(scope, a.getPropertyId()))
                .filter(a -> status == null || a.getStatus() == status)
                .filter(a -> propertyId == null || a.getPropertyId().equals(propertyId))
                .filter(a -> tenantId == null || a.getTenantId().equals(tenantId))
                .toList()).stream()
                .filter(v -> query.isEmpty() || contains(query, v.code(), v.tenantName(), v.unitNo(), v.propertyName()))
                .sorted(Comparator.comparing((AgreementView v) -> !v.status().isCurrent())
                        .thenComparing(AgreementView::endDate))
                .toList();
    }

    @Transactional(readOnly = true)
    public AgreementView get(UUID id) {
        return views(List.of(load(id))).get(0);
    }

    /** All agreements of one tenant (tenant portal: no staff check). */
    @Transactional(readOnly = true)
    public List<AgreementView> forTenant(UUID tenantId) {
        return views(agreements.findByTenantId(tenantId).stream()
                .filter(a -> a.getStatus() != Agreement.Status.DRAFT)
                .sorted(Comparator.comparing(Agreement::getStartDate).reversed())
                .toList());
    }

    // ---------------------------------------------------------------- Commands

    @Transactional
    public AgreementView create(AgreementRequest r) {
        guard.requireStaff();
        Unit unit = AccessGuard.owned(units.findById(r.unitId()), "Unit");
        guard.checkProperty(unit.getPropertyId());
        Tenant tenant = AccessGuard.owned(tenants.findById(r.tenantId()), "Tenant");
        Agreement a = new Agreement();
        a.setCode(codes.next("AG"));
        apply(a, r, unit);
        if (r.activate()) {
            validateActivation(a, unit);
        }
        agreements.save(a);
        audit.log("Created Agreement", "Agreement", a.getCode(), tenant.getName() + " • " + unit.getUnitNo());
        if (r.activate()) {
            doActivate(a, unit, tenant);
        }
        return get(a.getId());
    }

    @Transactional
    public AgreementView updateDraft(UUID id, AgreementRequest r) {
        Agreement a = load(id);
        if (a.getStatus() != Agreement.Status.DRAFT) {
            throw new BusinessException("Only draft agreements can be edited. Renew or terminate instead.");
        }
        Unit unit = AccessGuard.owned(units.findById(r.unitId()), "Unit");
        guard.checkProperty(unit.getPropertyId());
        AccessGuard.owned(tenants.findById(r.tenantId()), "Tenant");
        apply(a, r, unit);
        audit.log("Updated Agreement", "Agreement", a.getCode(), null);
        if (r.activate()) {
            return activate(id);
        }
        return get(id);
    }

    @Transactional
    public AgreementView activate(UUID id) {
        Agreement a = load(id);
        if (a.getStatus() != Agreement.Status.DRAFT) {
            throw new BusinessException("Only draft agreements can be activated");
        }
        Unit unit = units.findById(a.getUnitId()).orElseThrow();
        validateActivation(a, unit);
        doActivate(a, unit, tenants.findById(a.getTenantId()).orElseThrow());
        return get(id);
    }

    @Transactional
    public AgreementView terminate(UUID id, TerminateRequest r) {
        Agreement a = load(id);
        if (!a.getStatus().isCurrent() && a.getStatus() != Agreement.Status.DRAFT) {
            throw new BusinessException("Only draft or active agreements can be terminated");
        }
        boolean wasCurrent = a.getStatus().isCurrent();
        a.setStatus(Agreement.Status.TERMINATED);
        a.setTerminatedOn(r.date() == null ? clock.today() : r.date());
        a.setTerminationReason(r.reason().trim());
        agreements.flush();
        if (wasCurrent) {
            release(a);
        }
        audit.log("Terminated Agreement", "Agreement", a.getCode(), r.reason());
        notifications.notifyTenant(a.getTenantId(), "Agreement terminated",
                "Agreement " + a.getCode() + " was terminated on " + DATE.format(a.getTerminatedOn()) + ".", null);
        return get(id);
    }

    /** The old agreement ends; a new active one starts the next day. The unit stays occupied. */
    @Transactional
    public AgreementView renew(UUID id, RenewRequest r) {
        Agreement old = load(id);
        if (!old.getStatus().isCurrent() && old.getStatus() != Agreement.Status.EXPIRED) {
            throw new BusinessException("Only active, expiring or expired agreements can be renewed");
        }
        agreements.findByUnitIdAndStatusIn(old.getUnitId(), Agreement.CURRENT)
                .filter(other -> !other.getId().equals(old.getId()))
                .ifPresent(other -> {
                    throw BusinessException.conflict("The unit has been let to another tenant");
                });
        agreements.findByTenantIdAndStatusIn(old.getTenantId(), Agreement.CURRENT)
                .filter(other -> !other.getId().equals(old.getId()))
                .ifPresent(other -> {
                    throw BusinessException.conflict("The tenant already has another active agreement");
                });
        LocalDate start = old.getEndDate().plusDays(1);
        if (!r.newEndDate().isAfter(start)) {
            throw new BusinessException("New end date must be after " + DATE.format(start));
        }
        old.setStatus(Agreement.Status.EXPIRED);
        agreements.flush();

        Agreement renewed = new Agreement();
        renewed.setCode(codes.next("AG"));
        renewed.setTenantId(old.getTenantId());
        renewed.setUnitId(old.getUnitId());
        renewed.setPropertyId(old.getPropertyId());
        renewed.setStartDate(start);
        renewed.setEndDate(r.newEndDate());
        renewed.setMonthlyRent(r.monthlyRent() == null ? old.getMonthlyRent() : Money.of(r.monthlyRent()));
        renewed.setServiceCharge(old.getServiceCharge());
        renewed.setUtilityCharge(old.getUtilityCharge());
        renewed.setOtherCharge(old.getOtherCharge());
        renewed.setSecurityDeposit(old.getSecurityDeposit());
        renewed.setDueDay(old.getDueDay());
        renewed.setTerms(old.getTerms());
        renewed.setRenewedFromId(old.getId());
        renewed.setStatus(Agreement.Status.ACTIVE);
        agreements.save(renewed);
        units.findById(old.getUnitId()).ifPresent(u -> u.setStatus(Unit.Status.OCCUPIED));
        tenants.findById(old.getTenantId()).ifPresent(t -> t.setStatus(Tenant.Status.ACTIVE));
        audit.log("Renewed Agreement", "Agreement", renewed.getCode(),
                "Renewed from " + old.getCode() + " until " + DATE.format(r.newEndDate()));
        notifications.notifyTenant(old.getTenantId(), "Agreement renewed",
                "Your agreement was renewed until " + DATE.format(r.newEndDate()) + " (" + renewed.getCode() + ").", null);
        refreshStatuses();
        return get(renewed.getId());
    }

    /**
     * Marks agreements Expiring / Expired according to today's date and the organisation's alert
     * window, releasing units of expired agreements. Idempotent; run daily and after changes.
     */
    @Transactional
    public void refreshStatuses() {
        LocalDate today = clock.today();
        LocalDate alertLimit = today.plusDays(orgs.current().getExpiryAlertDays());
        for (Agreement a : agreements.findByStatusIn(Agreement.CURRENT)) {
            String label = a.getCode() + " (" + tenantName(a.getTenantId()) + ", " + unitLabel(a.getUnitId()) + ")";
            if (a.getEndDate().isBefore(today)) {
                a.setStatus(Agreement.Status.EXPIRED);
                agreements.flush();
                release(a);
                audit.log("Agreement Expired", "Agreement", a.getCode(), null);
                notifications.notifyStaff(a.getPropertyId(), "Agreement expired",
                        label + " expired on " + DATE.format(a.getEndDate()) + ".", "expired:" + a.getId());
                notifications.notifyTenant(a.getTenantId(), "Agreement expired",
                        "Your agreement " + a.getCode() + " expired on " + DATE.format(a.getEndDate()) + ".",
                        "expired:" + a.getId());
            } else if (!a.getEndDate().isAfter(alertLimit)) {
                a.setStatus(Agreement.Status.EXPIRING);
                notifications.notifyStaff(a.getPropertyId(), "Agreement expiring soon",
                        label + " ends on " + DATE.format(a.getEndDate()) + ".", "expiring:" + a.getId());
                notifications.notifyTenant(a.getTenantId(), "Agreement expiring soon",
                        "Your agreement " + a.getCode() + " ends on " + DATE.format(a.getEndDate())
                                + ". Contact management to renew.", "expiring:" + a.getId());
            } else {
                a.setStatus(Agreement.Status.ACTIVE);
            }
        }
    }

    // ---------------------------------------------------------------- Helpers

    public Agreement load(UUID id) {
        guard.requireStaff();
        Agreement a = AccessGuard.owned(agreements.findById(id), "Agreement");
        guard.checkProperty(a.getPropertyId());
        return a;
    }

    private void apply(Agreement a, AgreementRequest r, Unit unit) {
        if (!r.endDate().isAfter(r.startDate())) {
            throw new BusinessException("End date must be after start date");
        }
        a.setTenantId(r.tenantId());
        a.setUnitId(unit.getId());
        a.setPropertyId(unit.getPropertyId());
        a.setStartDate(r.startDate());
        a.setEndDate(r.endDate());
        a.setMonthlyRent(Money.of(r.monthlyRent() == null ? unit.getMonthlyRent() : r.monthlyRent()));
        a.setServiceCharge(Money.of(r.serviceCharge() == null ? unit.getServiceCharge() : r.serviceCharge()));
        a.setUtilityCharge(Money.of(r.utilityCharge() == null ? unit.getUtilityCharge() : r.utilityCharge()));
        a.setOtherCharge(Money.of(r.otherCharge() == null ? unit.getOtherCharge() : r.otherCharge()));
        a.setSecurityDeposit(Money.of(r.securityDeposit() == null ? unit.getSecurityDeposit() : r.securityDeposit()));
        a.setAdvanceAmount(Money.of(r.advanceAmount()));
        a.setDueDay(r.dueDay() == null ? 5 : r.dueDay());
        a.setDocumentRef(r.documentRef());
        a.setTerms(r.terms());
        if (a.getMonthlyRent().signum() <= 0) {
            throw new BusinessException("Monthly rent must be greater than zero");
        }
    }

    private void validateActivation(Agreement a, Unit unit) {
        agreements.findByUnitIdAndStatusIn(unit.getId(), Agreement.CURRENT)
                .filter(other -> !other.getId().equals(a.getId()))
                .ifPresent(other -> {
                    throw BusinessException.conflict("Unit " + unit.getUnitNo() + " already has an active agreement ("
                            + other.getCode() + ")");
                });
        agreements.findByTenantIdAndStatusIn(a.getTenantId(), Agreement.CURRENT)
                .filter(other -> !other.getId().equals(a.getId()))
                .ifPresent(other -> {
                    throw BusinessException.conflict("Tenant already has an active agreement (" + other.getCode() + ")");
                });
        if (unit.getStatus() == Unit.Status.INACTIVE || unit.getStatus() == Unit.Status.MAINTENANCE) {
            throw new BusinessException("Unit " + unit.getUnitNo() + " is " + unit.getStatus().name().toLowerCase()
                    + " and cannot be let");
        }
        Property p = properties.findById(unit.getPropertyId()).orElseThrow();
        if (p.getStatus() == Property.Status.INACTIVE) {
            throw new BusinessException("Property " + p.getName() + " is inactive");
        }
        if (a.getEndDate().isBefore(clock.today())) {
            throw new BusinessException("Agreement end date is in the past");
        }
    }

    private void doActivate(Agreement a, Unit unit, Tenant tenant) {
        a.setStatus(Agreement.Status.ACTIVE);
        unit.setStatus(Unit.Status.OCCUPIED);
        tenant.setStatus(Tenant.Status.ACTIVE);
        agreements.flush();
        audit.log("Activated Agreement", "Agreement", a.getCode(), tenant.getName() + " moved into " + unit.getUnitNo());
        notifications.notifyTenant(tenant.getId(), "Agreement activated",
                "Your rental agreement " + a.getCode() + " for unit " + unit.getUnitNo() + " is now active.", null);
        refreshStatuses();
    }

    /** Tenant moves out: frees the unit and marks the tenant as a previous tenant. */
    private void release(Agreement a) {
        if (agreements.findByUnitIdAndStatusIn(a.getUnitId(), Agreement.CURRENT).isEmpty()) {
            units.findById(a.getUnitId()).ifPresent(u -> u.setStatus(Unit.Status.AVAILABLE));
        }
        if (agreements.findByTenantIdAndStatusIn(a.getTenantId(), Agreement.CURRENT).isEmpty()) {
            tenants.findById(a.getTenantId()).ifPresent(t -> t.setStatus(Tenant.Status.PREVIOUS));
        }
    }

    private String tenantName(UUID id) {
        return tenants.findById(id).map(Tenant::getName).orElse("-");
    }

    private String unitLabel(UUID id) {
        return units.findById(id).map(Unit::getUnitNo).orElse("-");
    }

    List<AgreementView> views(List<Agreement> list) {
        Map<UUID, Tenant> t = tenants.findAllById(list.stream().map(Agreement::getTenantId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Tenant::getId, Function.identity()));
        Map<UUID, Unit> u = units.findAllById(list.stream().map(Agreement::getUnitId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Unit::getId, Function.identity()));
        Map<UUID, Property> p = properties.findAllById(list.stream().map(Agreement::getPropertyId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Property::getId, Function.identity()));
        LocalDate today = clock.today();
        return list.stream().map(a -> new AgreementView(a.getId(), a.getCode(), a.getTenantId(),
                name(t.get(a.getTenantId())), a.getUnitId(),
                u.containsKey(a.getUnitId()) ? u.get(a.getUnitId()).getUnitNo() : null, a.getPropertyId(),
                p.containsKey(a.getPropertyId()) ? p.get(a.getPropertyId()).getName() : null,
                a.getStartDate(), a.getEndDate(),
                a.getStatus().isCurrent() ? ChronoUnit.DAYS.between(today, a.getEndDate()) : null,
                a.getMonthlyRent(), a.getServiceCharge(), a.getUtilityCharge(), a.getOtherCharge(), a.totalMonthly(),
                a.getSecurityDeposit(), a.getAdvanceAmount(), a.getDueDay(), a.getDocumentRef(), a.getTerms(),
                a.getStatus(), a.getRenewedFromId(), a.getTerminatedOn(), a.getTerminationReason())).toList();
    }

    private static String name(Tenant t) {
        return t == null ? null : t.getName();
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
