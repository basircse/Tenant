package com.revesoft.tms.report;

import com.revesoft.tms.agreement.Agreement;
import com.revesoft.tms.agreement.AgreementRepository;
import com.revesoft.tms.common.BusinessClock;
import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.common.Money;
import com.revesoft.tms.expense.ExpenseService;
import com.revesoft.tms.expense.ExpenseService.ExpenseView;
import com.revesoft.tms.org.OrganizationService;
import com.revesoft.tms.payment.Payment;
import com.revesoft.tms.payment.PaymentService;
import com.revesoft.tms.payment.PaymentService.PaymentView;
import com.revesoft.tms.property.Property;
import com.revesoft.tms.property.PropertyRepository;
import com.revesoft.tms.property.Unit;
import com.revesoft.tms.property.UnitRepository;
import com.revesoft.tms.rent.RentInvoice;
import com.revesoft.tms.rent.RentInvoiceRepository;
import com.revesoft.tms.rent.RentService;
import com.revesoft.tms.rent.RentService.InvoiceView;
import com.revesoft.tms.security.AccessGuard;
import com.revesoft.tms.tenant.Tenant;
import com.revesoft.tms.tenant.TenantRepository;
import com.revesoft.tms.tenant.TenantService;
import com.revesoft.tms.tenant.TenantService.TenantView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the management reports as generic {@link ReportTable}s. Staff only; managers see only their
 * assigned properties (the same scope rules as the list screens).
 */
@Service
public class ReportService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH);

    /** Report types, in the order the apps should list them. */
    public enum Type {
        RENT_ROLL("rent-roll", "Rent Roll", false),
        COLLECTIONS("collections", "Collections", true),
        DUES("dues", "Outstanding Dues", false),
        EXPENSES("expenses", "Expenses", true),
        PROFIT_LOSS("profit-loss", "Profit & Loss", true),
        OCCUPANCY("occupancy", "Occupancy", false),
        TENANTS("tenants", "Tenants", false);

        final String slug;
        final String title;
        /** Uses the from / to / month period; the others are a snapshot as of today. */
        final boolean period;

        Type(String slug, String title, boolean period) {
            this.slug = slug;
            this.title = title;
            this.period = period;
        }

        static Type of(String slug) {
            return Arrays.stream(values()).filter(t -> t.slug.equalsIgnoreCase(slug)).findFirst()
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REPORT_TYPE",
                            "Unknown report \"" + slug + "\""));
        }
    }

    public record ReportInfo(String type, String title, boolean usesPeriod) {
    }

    public record Filter(LocalDate from, LocalDate to, YearMonth month, UUID propertyId) {
    }

    /** A built report plus what the exporters need. */
    public record Result(ReportTable table, String orgName, String generatedAt, String fileBaseName) {
    }

    private record Period(LocalDate from, LocalDate to, String label, String text) {
        boolean contains(LocalDate d) {
            return (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to));
        }
    }

    private final PropertyRepository properties;
    private final UnitRepository units;
    private final AgreementRepository agreements;
    private final RentInvoiceRepository invoices;
    private final TenantRepository tenants;
    private final PaymentService payments;
    private final ExpenseService expenses;
    private final RentService rent;
    private final TenantService tenantService;
    private final OrganizationService orgs;
    private final AccessGuard guard;
    private final BusinessClock clock;

    public ReportService(PropertyRepository properties, UnitRepository units, AgreementRepository agreements,
                         RentInvoiceRepository invoices, TenantRepository tenants, PaymentService payments,
                         ExpenseService expenses, RentService rent, TenantService tenantService,
                         OrganizationService orgs, AccessGuard guard, BusinessClock clock) {
        this.properties = properties;
        this.units = units;
        this.agreements = agreements;
        this.invoices = invoices;
        this.tenants = tenants;
        this.payments = payments;
        this.expenses = expenses;
        this.rent = rent;
        this.tenantService = tenantService;
        this.orgs = orgs;
        this.guard = guard;
        this.clock = clock;
    }

    public List<ReportInfo> types() {
        guard.requireStaff();
        return Arrays.stream(Type.values()).map(t -> new ReportInfo(t.slug, t.title, t.period)).toList();
    }

    @Transactional(readOnly = true)
    public Result build(String slug, Filter f) {
        guard.requireStaff();
        Type type = Type.of(slug);
        Property property = null;
        if (f.propertyId() != null) {
            property = AccessGuard.owned(properties.findById(f.propertyId()), "Property");
            guard.checkProperty(property.getId());
        }
        Period period = period(f, type.period);
        String sub = period.text() + (property == null ? "" : " · " + property.getName());
        ReportTable table = switch (type) {
            case RENT_ROLL -> rentRoll(type, sub, f.propertyId());
            case COLLECTIONS -> collections(type, sub, period, f.propertyId());
            case DUES -> dues(type, sub, f);
            case EXPENSES -> expenses(type, sub, period, f.propertyId());
            case PROFIT_LOSS -> profitLoss(type, sub, period, f.propertyId());
            case OCCUPANCY -> occupancy(type, sub, f.propertyId());
            case TENANTS -> tenantList(type, sub, f.propertyId());
        };
        String generatedAt = LocalDateTime.ofInstant(clock.now(), clock.zone()).format(STAMP);
        return new Result(table, orgs.current().getName(), generatedAt, type.slug + "-" + period.label());
    }

    // ---------------------------------------------------------------- Reports

    private ReportTable rentRoll(Type type, String sub, UUID propertyId) {
        Set<UUID> scope = guard.propertyScope();
        Map<UUID, Property> props = propertyMap();
        Map<UUID, Agreement> current = agreements.findByStatusIn(Agreement.CURRENT).stream()
                .collect(Collectors.toMap(Agreement::getUnitId, Function.identity(), (a, b) -> a));
        Map<UUID, Tenant> tenantMap = tenants.findAll().stream().collect(Collectors.toMap(Tenant::getId, Function.identity()));
        Map<UUID, BigDecimal> dueByUnit = invoices.findByStatusIn(RentInvoice.Status.OPEN).stream()
                .collect(Collectors.groupingBy(RentInvoice::getUnitId,
                        Collectors.reducing(BigDecimal.ZERO, RentInvoice::dueAmount, BigDecimal::add)));
        List<Unit> list = units.findAll().stream()
                .filter(u -> AccessGuard.inScope(scope, u.getPropertyId()))
                .filter(u -> propertyId == null || u.getPropertyId().equals(propertyId))
                .filter(u -> u.getStatus() != Unit.Status.INACTIVE)
                .sorted(Comparator.comparing((Unit u) -> name(props, u.getPropertyId()))
                        .thenComparing(Unit::getFloor).thenComparing(Unit::getUnitNo))
                .toList();
        ReportTable.Builder b = new ReportTable.Builder(type.title, sub)
                .column("property", "Property", ReportTable.TEXT)
                .column("unit", "Unit", ReportTable.TEXT)
                .column("floor", "Floor", ReportTable.TEXT)
                .column("status", "Status", ReportTable.TEXT)
                .column("tenant", "Tenant", ReportTable.TEXT)
                .column("agreementEnd", "Agreement Ends", ReportTable.DATE)
                .column("monthlyRent", "Monthly Rent", ReportTable.MONEY)
                .column("outstanding", "Outstanding", ReportTable.MONEY);
        BigDecimal rentTotal = BigDecimal.ZERO;
        BigDecimal dueTotal = BigDecimal.ZERO;
        int occupied = 0;
        for (Unit u : list) {
            Agreement a = current.get(u.getId());
            Tenant t = a == null ? null : tenantMap.get(a.getTenantId());
            BigDecimal monthly = a == null ? u.totalMonthly() : a.totalMonthly();
            BigDecimal due = Money.of(dueByUnit.getOrDefault(u.getId(), BigDecimal.ZERO));
            rentTotal = rentTotal.add(monthly);
            dueTotal = dueTotal.add(due);
            if (u.getStatus() == Unit.Status.OCCUPIED) {
                occupied++;
            }
            b.row(name(props, u.getPropertyId()), u.getUnitNo(), u.getFloor(), label(u.getStatus().name()),
                    t == null ? null : t.getName(), a == null ? null : a.getEndDate(), monthly, due);
        }
        return b.totals("property", "Total (" + list.size() + " units)", "monthlyRent", Money.of(rentTotal),
                        "outstanding", Money.of(dueTotal))
                .summary("Occupied units", occupied, ReportTable.NUMBER)
                .summary("Other units", list.size() - occupied, ReportTable.NUMBER)
                .build();
    }

    private ReportTable collections(Type type, String sub, Period p, UUID propertyId) {
        List<PaymentView> list = payments.list(p.from(), p.to(), null, propertyId, null, false).items().stream()
                .sorted(Comparator.comparing(PaymentView::paymentDate).thenComparing(PaymentView::receiptNo))
                .toList();
        ReportTable.Builder b = new ReportTable.Builder(type.title, sub)
                .column("date", "Date", ReportTable.DATE)
                .column("receiptNo", "Receipt No", ReportTable.TEXT)
                .column("tenant", "Tenant", ReportTable.TEXT)
                .column("property", "Property", ReportTable.TEXT)
                .column("unit", "Unit", ReportTable.TEXT)
                .column("month", "Rent Month", ReportTable.TEXT)
                .column("method", "Method", ReportTable.TEXT)
                .column("reference", "Reference", ReportTable.TEXT)
                .column("amount", "Amount", ReportTable.MONEY);
        Map<Payment.Method, BigDecimal> byMethod = new TreeMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PaymentView v : list) {
            b.row(v.paymentDate(), v.receiptNo(), v.tenantName(), v.propertyName(), v.unitNo(),
                    v.billingMonth() == null ? null : MONTH.format(v.billingMonth()), label(v.method().name()),
                    v.referenceNo(), v.amount());
            byMethod.merge(v.method(), v.amount(), BigDecimal::add);
            total = total.add(v.amount());
        }
        b.totals("date", "Total (" + list.size() + " payments)", "amount", Money.of(total));
        byMethod.forEach((m, amount) -> b.summary(label(m.name()), Money.of(amount), ReportTable.MONEY));
        return b.build();
    }

    private ReportTable dues(Type type, String sub, Filter f) {
        List<InvoiceView> list = rent.dues(f.propertyId(), null, null, f.month(), null).items();
        ReportTable.Builder b = new ReportTable.Builder(type.title, sub)
                .column("invoice", "Invoice", ReportTable.TEXT)
                .column("tenant", "Tenant", ReportTable.TEXT)
                .column("property", "Property", ReportTable.TEXT)
                .column("unit", "Unit", ReportTable.TEXT)
                .column("month", "Rent Month", ReportTable.TEXT)
                .column("dueDate", "Due Date", ReportTable.DATE)
                .column("total", "Billed", ReportTable.MONEY)
                .column("paid", "Paid", ReportTable.MONEY)
                .column("due", "Due", ReportTable.MONEY)
                .column("daysOverdue", "Days Overdue", ReportTable.NUMBER)
                .column("status", "Status", ReportTable.TEXT);
        BigDecimal billed = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal due = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        int overdueCount = 0;
        for (InvoiceView v : list) {
            b.row(v.code(), v.tenantName(), v.propertyName(), v.unitNo(), MONTH.format(v.billingMonth()), v.dueDate(),
                    v.totalAmount(), v.paidAmount(), v.dueAmount(), v.daysOverdue(), label(v.status().name()));
            billed = billed.add(v.totalAmount());
            paid = paid.add(v.paidAmount());
            due = due.add(v.dueAmount());
            if (v.daysOverdue() > 0) {
                overdueCount++;
                overdue = overdue.add(v.dueAmount());
            }
        }
        return b.totals("invoice", "Total (" + list.size() + " invoices)", "total", Money.of(billed),
                        "paid", Money.of(paid), "due", Money.of(due))
                .summary("Overdue invoices", overdueCount, ReportTable.NUMBER)
                .summary("Overdue amount", Money.of(overdue), ReportTable.MONEY)
                .build();
    }

    private ReportTable expenses(Type type, String sub, Period p, UUID propertyId) {
        List<ExpenseView> list = expenses.list(propertyId, null, p.from(), p.to()).items().stream()
                .sorted(Comparator.comparing(ExpenseView::expenseDate)).toList();
        ReportTable.Builder b = new ReportTable.Builder(type.title, sub)
                .column("date", "Date", ReportTable.DATE)
                .column("category", "Category", ReportTable.TEXT)
                .column("property", "Property", ReportTable.TEXT)
                .column("description", "Description", ReportTable.TEXT)
                .column("amount", "Amount", ReportTable.MONEY);
        Map<String, BigDecimal> byCategory = new TreeMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ExpenseView e : list) {
            b.row(e.expenseDate(), e.category(), e.propertyId() == null ? "General" : e.propertyName(),
                    e.description(), e.amount());
            byCategory.merge(e.category(), e.amount(), BigDecimal::add);
            total = total.add(e.amount());
        }
        b.totals("date", "Total (" + list.size() + " expenses)", "amount", Money.of(total));
        byCategory.forEach((c, amount) -> b.summary(c, Money.of(amount), ReportTable.MONEY));
        return b.build();
    }

    private ReportTable profitLoss(Type type, String sub, Period p, UUID propertyId) {
        Set<UUID> scope = guard.propertyScope();
        Map<UUID, BigDecimal> collected = payments.list(p.from(), p.to(), null, propertyId, null, false).items()
                .stream().collect(Collectors.groupingBy(PaymentView::propertyId,
                        Collectors.reducing(BigDecimal.ZERO, PaymentView::amount, BigDecimal::add)));
        List<ExpenseView> exp = expenses.list(propertyId, null, p.from(), p.to()).items();
        Map<UUID, BigDecimal> spent = exp.stream().filter(e -> e.propertyId() != null)
                .collect(Collectors.groupingBy(ExpenseView::propertyId,
                        Collectors.reducing(BigDecimal.ZERO, ExpenseView::amount, BigDecimal::add)));
        BigDecimal general = exp.stream().filter(e -> e.propertyId() == null).map(ExpenseView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        ReportTable.Builder b = new ReportTable.Builder(type.title, sub)
                .column("property", "Property", ReportTable.TEXT)
                .column("collected", "Rent Collected", ReportTable.MONEY)
                .column("expenses", "Expenses", ReportTable.MONEY)
                .column("net", "Net", ReportTable.MONEY)
                .column("margin", "Margin", ReportTable.PERCENT);
        BigDecimal totalIn = BigDecimal.ZERO;
        BigDecimal totalOut = BigDecimal.ZERO;
        for (Property prop : properties.findAllByOrderByNameAsc()) {
            if (!AccessGuard.inScope(scope, prop.getId()) || (propertyId != null && !propertyId.equals(prop.getId()))) {
                continue;
            }
            BigDecimal in = Money.of(collected.getOrDefault(prop.getId(), BigDecimal.ZERO));
            BigDecimal out = Money.of(spent.getOrDefault(prop.getId(), BigDecimal.ZERO));
            b.row(prop.getName(), in, out, Money.of(in.subtract(out)), margin(in, out));
            totalIn = totalIn.add(in);
            totalOut = totalOut.add(out);
        }
        if (general.signum() > 0) {
            b.row("General (all properties)", Money.of(BigDecimal.ZERO), Money.of(general), Money.of(general.negate()),
                    null);
            totalOut = totalOut.add(general);
        }
        return b.totals("property", "Total", "collected", Money.of(totalIn), "expenses", Money.of(totalOut),
                        "net", Money.of(totalIn.subtract(totalOut)), "margin", margin(totalIn, totalOut))
                .build();
    }

    private ReportTable occupancy(Type type, String sub, UUID propertyId) {
        Set<UUID> scope = guard.propertyScope();
        Map<UUID, List<Unit>> byProperty = units.findAll().stream()
                .filter(u -> u.getStatus() != Unit.Status.INACTIVE)
                .collect(Collectors.groupingBy(Unit::getPropertyId));
        ReportTable.Builder b = new ReportTable.Builder(type.title, sub)
                .column("property", "Property", ReportTable.TEXT)
                .column("units", "Units", ReportTable.NUMBER)
                .column("occupied", "Occupied", ReportTable.NUMBER)
                .column("vacant", "Vacant", ReportTable.NUMBER)
                .column("other", "Reserved / Maintenance", ReportTable.NUMBER)
                .column("occupancy", "Occupancy", ReportTable.PERCENT);
        int total = 0;
        int occ = 0;
        int vac = 0;
        int other = 0;
        for (Property prop : properties.findAllByOrderByNameAsc()) {
            if (!AccessGuard.inScope(scope, prop.getId()) || (propertyId != null && !propertyId.equals(prop.getId()))) {
                continue;
            }
            List<Unit> list = byProperty.getOrDefault(prop.getId(), List.of());
            int o = (int) list.stream().filter(u -> u.getStatus() == Unit.Status.OCCUPIED).count();
            int v = (int) list.stream().filter(u -> u.getStatus() == Unit.Status.AVAILABLE).count();
            b.row(prop.getName(), list.size(), o, v, list.size() - o - v, percent(o, list.size()));
            total += list.size();
            occ += o;
            vac += v;
            other += list.size() - o - v;
        }
        return b.totals("property", "Total", "units", total, "occupied", occ, "vacant", vac, "other", other,
                "occupancy", percent(occ, total)).build();
    }

    private ReportTable tenantList(Type type, String sub, UUID propertyId) {
        List<TenantView> list = tenantService.list(null, null, propertyId);
        ReportTable.Builder b = new ReportTable.Builder(type.title, sub)
                .column("code", "Code", ReportTable.TEXT)
                .column("name", "Name", ReportTable.TEXT)
                .column("phone", "Phone", ReportTable.TEXT)
                .column("property", "Property", ReportTable.TEXT)
                .column("unit", "Unit", ReportTable.TEXT)
                .column("status", "Status", ReportTable.TEXT)
                .column("monthlyRent", "Monthly Rent", ReportTable.MONEY)
                .column("outstanding", "Outstanding", ReportTable.MONEY);
        BigDecimal rentTotal = BigDecimal.ZERO;
        BigDecimal dueTotal = BigDecimal.ZERO;
        for (TenantView t : list) {
            var cu = t.currentUnit();
            b.row(t.code(), t.name(), t.mobile(), cu == null ? null : cu.propertyName(), cu == null ? null : cu.unitNo(),
                    label(t.status().name()), cu == null ? null : cu.monthlyTotal(), t.outstanding());
            if (cu != null) {
                rentTotal = rentTotal.add(cu.monthlyTotal());
            }
            dueTotal = dueTotal.add(t.outstanding());
        }
        return b.totals("code", "Total (" + list.size() + " tenants)", "monthlyRent", Money.of(rentTotal),
                "outstanding", Money.of(dueTotal)).build();
    }

    // ---------------------------------------------------------------- Helpers

    /** Period reports default to the current month; snapshot reports are "as of today". */
    private Period period(Filter f, boolean usesPeriod) {
        LocalDate today = clock.today();
        if (!usesPeriod) {
            String text = "As of " + DAY.format(today) + (f.month() != null ? " · " + MONTH.format(f.month()) : "");
            return new Period(null, null, f.month() != null ? f.month().toString() : today.toString(), text);
        }
        if (f.month() != null) {
            return new Period(f.month().atDay(1), f.month().atEndOfMonth(), f.month().toString(),
                    MONTH.format(f.month()));
        }
        if (f.from() == null && f.to() == null) {
            YearMonth m = YearMonth.from(today);
            return new Period(m.atDay(1), m.atEndOfMonth(), m.toString(), MONTH.format(m));
        }
        if (f.from() != null && f.to() != null && f.to().isBefore(f.from())) {
            throw new BusinessException("'to' must not be before 'from'");
        }
        String label = (f.from() == null ? "start" : f.from().toString()) + "_"
                + (f.to() == null ? today.toString() : f.to().toString());
        String text = (f.from() == null ? "Up to " : DAY.format(f.from()) + " - ")
                + (f.to() == null ? DAY.format(today) : DAY.format(f.to()));
        return new Period(f.from(), f.to(), label, text);
    }

    private Map<UUID, Property> propertyMap() {
        return properties.findAll().stream().collect(Collectors.toMap(Property::getId, Function.identity()));
    }

    private static String name(Map<UUID, Property> props, UUID id) {
        Property p = props.get(id);
        return p == null ? "" : p.getName();
    }

    /** OCCUPIED → Occupied, BANK_TRANSFER → Bank Transfer. */
    static String label(String enumName) {
        return Arrays.stream(enumName.toLowerCase(Locale.ROOT).split("_"))
                .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(Collectors.joining(" "));
    }

    private static BigDecimal margin(BigDecimal in, BigDecimal out) {
        if (in.signum() <= 0) {
            return null;
        }
        return in.subtract(out).multiply(BigDecimal.valueOf(100)).divide(in, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(int part, int whole) {
        return whole == 0 ? BigDecimal.ZERO.setScale(1)
                : BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }
}
