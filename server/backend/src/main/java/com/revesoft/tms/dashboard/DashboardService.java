package com.revesoft.tms.dashboard;

import com.revesoft.tms.agreement.Agreement;
import com.revesoft.tms.agreement.AgreementRepository;
import com.revesoft.tms.common.BusinessClock;
import com.revesoft.tms.common.Money;
import com.revesoft.tms.common.NameLookup;
import com.revesoft.tms.common.NameLookup.Names;
import com.revesoft.tms.maintenance.MaintenanceRepository;
import com.revesoft.tms.property.PropertyRepository;
import com.revesoft.tms.property.Unit;
import com.revesoft.tms.property.UnitRepository;
import com.revesoft.tms.rent.RentInvoice;
import com.revesoft.tms.rent.RentInvoiceRepository;
import com.revesoft.tms.security.AccessGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin / manager dashboard (SRS §5), scoped to the caller's properties. */
@Service
public class DashboardService {

    private final PropertyRepository properties;
    private final UnitRepository units;
    private final AgreementRepository agreements;
    private final RentInvoiceRepository invoices;
    private final MaintenanceRepository maintenance;
    private final NameLookup names;
    private final AccessGuard guard;
    private final BusinessClock clock;

    public DashboardService(PropertyRepository properties, UnitRepository units, AgreementRepository agreements,
                            RentInvoiceRepository invoices, MaintenanceRepository maintenance, NameLookup names,
                            AccessGuard guard, BusinessClock clock) {
        this.properties = properties;
        this.units = units;
        this.agreements = agreements;
        this.invoices = invoices;
        this.maintenance = maintenance;
        this.names = names;
        this.guard = guard;
        this.clock = clock;
    }

    public record Expiring(UUID agreementId, String code, String tenantName, String propertyName, String unitNo,
                           LocalDate endDate, long daysLeft) {
    }

    public record Overdue(UUID invoiceId, String code, String tenantName, String propertyName, String unitNo,
                          LocalDate billingMonth, BigDecimal dueAmount, long daysOverdue) {
    }

    public record MonthTrend(YearMonth month, BigDecimal billed, BigDecimal collected) {
    }

    public record Dashboard(int properties, int units, int occupied, int vacant, BigDecimal occupancyPercent,
                            int activeTenants, YearMonth month, BigDecimal expectedRent, BigDecimal collectedRent,
                            BigDecimal outstandingRent, int pendingMaintenance, int expiringAgreements,
                            List<Expiring> expiringSoon, List<Overdue> topOverdue, List<MonthTrend> trend) {
    }

    @Transactional(readOnly = true)
    public Dashboard get() {
        guard.requireStaff();
        Set<UUID> scope = guard.propertyScope();
        LocalDate today = clock.today();
        YearMonth month = YearMonth.from(today);

        int propertyCount = (int) properties.findAll().stream().filter(p -> AccessGuard.inScope(scope, p.getId())).count();
        List<Unit> unitList = units.findAll().stream()
                .filter(u -> AccessGuard.inScope(scope, u.getPropertyId()))
                .filter(u -> u.getStatus() != Unit.Status.INACTIVE)
                .toList();
        int occupied = (int) unitList.stream().filter(u -> u.getStatus() == Unit.Status.OCCUPIED).count();
        int vacant = (int) unitList.stream().filter(u -> u.getStatus() == Unit.Status.AVAILABLE).count();
        BigDecimal occupancy = unitList.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(occupied * 100L).divide(BigDecimal.valueOf(unitList.size()), 1, RoundingMode.HALF_UP);

        List<Agreement> current = agreements.findByStatusIn(Agreement.CURRENT).stream()
                .filter(a -> AccessGuard.inScope(scope, a.getPropertyId())).toList();
        List<RentInvoice> inv = invoices.findAll().stream()
                .filter(i -> AccessGuard.inScope(scope, i.getPropertyId()))
                .filter(i -> i.getStatus() != RentInvoice.Status.CANCELLED)
                .toList();
        List<RentInvoice> thisMonth = inv.stream().filter(i -> i.getBillingMonth().equals(month.atDay(1))).toList();
        List<RentInvoice> overdue = inv.stream().filter(i -> i.getStatus() == RentInvoice.Status.OVERDUE)
                .sorted(Comparator.comparing(RentInvoice::dueAmount).reversed()).limit(5).toList();
        List<Agreement> expiring = current.stream().filter(a -> a.getStatus() == Agreement.Status.EXPIRING)
                .sorted(Comparator.comparing(Agreement::getEndDate)).toList();
        int pendingMaintenance = (int) maintenance.findAll().stream()
                .filter(m -> AccessGuard.inScope(scope, m.getPropertyId()))
                .filter(m -> m.getStatus().isPending()).count();

        Names n = names.load(
                java.util.stream.Stream.concat(expiring.stream().map(Agreement::getTenantId),
                        overdue.stream().map(RentInvoice::getTenantId)).collect(Collectors.toSet()),
                java.util.stream.Stream.concat(expiring.stream().map(Agreement::getUnitId),
                        overdue.stream().map(RentInvoice::getUnitId)).collect(Collectors.toSet()),
                java.util.stream.Stream.concat(expiring.stream().map(Agreement::getPropertyId),
                        overdue.stream().map(RentInvoice::getPropertyId)).collect(Collectors.toSet()));

        List<MonthTrend> trend = new java.util.ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            YearMonth m = month.minusMonths(i);
            List<RentInvoice> mi = inv.stream().filter(x -> x.getBillingMonth().equals(m.atDay(1))).toList();
            trend.add(new MonthTrend(m, sum(mi, RentInvoice::totalAmount), sum(mi, RentInvoice::getPaidAmount)));
        }

        return new Dashboard(propertyCount, unitList.size(), occupied, vacant, occupancy,
                (int) current.stream().map(Agreement::getTenantId).distinct().count(), month,
                sum(thisMonth, RentInvoice::totalAmount), sum(thisMonth, RentInvoice::getPaidAmount),
                sum(inv.stream().filter(i -> i.getStatus().isOpen()).toList(), RentInvoice::dueAmount),
                pendingMaintenance, expiring.size(),
                expiring.stream().limit(5).map(a -> new Expiring(a.getId(), a.getCode(), n.tenant(a.getTenantId()),
                        n.property(a.getPropertyId()), n.unit(a.getUnitId()), a.getEndDate(),
                        ChronoUnit.DAYS.between(today, a.getEndDate()))).toList(),
                overdue.stream().map(i -> new Overdue(i.getId(), i.getCode(), n.tenant(i.getTenantId()),
                        n.property(i.getPropertyId()), n.unit(i.getUnitId()), i.getBillingMonth(), i.dueAmount(),
                        i.daysOverdue(today))).toList(),
                trend);
    }

    private static BigDecimal sum(List<RentInvoice> list, java.util.function.Function<RentInvoice, BigDecimal> f) {
        return Money.of(list.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
