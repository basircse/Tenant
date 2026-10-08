package com.revesoft.tms.rent;

import com.revesoft.tms.agreement.Agreement;
import com.revesoft.tms.agreement.AgreementRepository;
import com.revesoft.tms.audit.AuditService;
import com.revesoft.tms.common.BusinessClock;
import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.common.CodeGenerator;
import com.revesoft.tms.common.Money;
import com.revesoft.tms.common.NameLookup;
import com.revesoft.tms.common.NameLookup.Names;
import com.revesoft.tms.notification.NotificationService;
import com.revesoft.tms.org.OrganizationService;
import com.revesoft.tms.payment.Payment;
import com.revesoft.tms.payment.PaymentRepository;
import com.revesoft.tms.security.AccessGuard;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Monthly rent generation, invoice status and dues (SRS §10, §12). */
@Service
public class RentService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private final RentInvoiceRepository invoices;
    private final AgreementRepository agreements;
    private final PaymentRepository payments;
    private final OrganizationService orgs;
    private final NameLookup names;
    private final CodeGenerator codes;
    private final AccessGuard guard;
    private final AuditService audit;
    private final NotificationService notifications;
    private final BusinessClock clock;

    public RentService(RentInvoiceRepository invoices, AgreementRepository agreements, PaymentRepository payments,
                       OrganizationService orgs, NameLookup names, CodeGenerator codes, AccessGuard guard,
                       AuditService audit, NotificationService notifications, BusinessClock clock) {
        this.invoices = invoices;
        this.agreements = agreements;
        this.payments = payments;
        this.orgs = orgs;
        this.names = names;
        this.codes = codes;
        this.guard = guard;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- DTOs

    public record GenerateRequest(@NotNull YearMonth month) {
    }

    public record GenerateResult(YearMonth month, int created) {
    }

    public record CancelRequest(@NotBlank String reason) {
    }

    public record InvoiceView(UUID id, String code, UUID tenantId, String tenantName, UUID agreementId, UUID unitId,
                              String unitNo, UUID propertyId, String propertyName, LocalDate billingMonth,
                              LocalDate dueDate, BigDecimal rentAmount, BigDecimal serviceCharge,
                              BigDecimal utilityCharge, BigDecimal otherCharge, BigDecimal totalAmount,
                              BigDecimal paidAmount, BigDecimal dueAmount, long daysOverdue,
                              RentInvoice.Status status, String cancelReason) {
    }

    public record InvoiceList(BigDecimal totalBilled, BigDecimal collected, BigDecimal outstanding,
                              List<InvoiceView> items) {
    }

    public record InvoicePayment(UUID id, String receiptNo, LocalDate paymentDate, BigDecimal amount,
                                 Payment.Method method, String referenceNo, boolean voided) {
    }

    public record InvoiceDetail(InvoiceView invoice, List<InvoicePayment> payments) {
    }

    // ---------------------------------------------------------------- Queries

    @Transactional(readOnly = true)
    public InvoiceList list(YearMonth month, UUID propertyId, RentInvoice.Status status, UUID tenantId, String q) {
        guard.requireStaff();
        Set<UUID> scope = guard.propertyScope();
        List<RentInvoice> list = (month == null ? invoices.findAll() : invoices.findByBillingMonth(month.atDay(1)))
                .stream()
                .filter(i -> AccessGuard.inScope(scope, i.getPropertyId()))
                .filter(i -> propertyId == null || i.getPropertyId().equals(propertyId))
                .filter(i -> status == null || i.getStatus() == status)
                .filter(i -> tenantId == null || i.getTenantId().equals(tenantId))
                .toList();
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<InvoiceView> views = views(list).stream()
                .filter(v -> query.isEmpty() || contains(query, v.code(), v.tenantName(), v.unitNo(), v.propertyName()))
                .sorted(Comparator.comparing(InvoiceView::billingMonth).reversed()
                        .thenComparing(v -> v.tenantName() == null ? "" : v.tenantName()))
                .toList();
        return summarize(views);
    }

    @Transactional(readOnly = true)
    public InvoiceDetail get(UUID id) {
        return detail(load(id));
    }

    /** Invoice detail without staff check (callers must verify ownership). */
    @Transactional(readOnly = true)
    public InvoiceDetail detail(RentInvoice i) {
        List<InvoicePayment> pays = payments.findByInvoiceIdOrderByPaymentDateAscCreatedAtAsc(i.getId()).stream()
                .map(p -> new InvoicePayment(p.getId(), p.getReceiptNo(), p.getPaymentDate(), p.getAmount(),
                        p.getMethod(), p.getReferenceNo(), p.isVoided()))
                .toList();
        return new InvoiceDetail(views(List.of(i)).get(0), pays);
    }

    /** Open invoices with a balance, most overdue first (SRS §12). */
    @Transactional(readOnly = true)
    public InvoiceList dues(UUID propertyId, UUID unitId, UUID tenantId, YearMonth month, RentInvoice.Status status) {
        guard.requireStaff();
        Set<UUID> scope = guard.propertyScope();
        List<RentInvoice> list = invoices.findByStatusIn(RentInvoice.Status.OPEN).stream()
                .filter(i -> i.dueAmount().signum() > 0)
                .filter(i -> AccessGuard.inScope(scope, i.getPropertyId()))
                .filter(i -> propertyId == null || i.getPropertyId().equals(propertyId))
                .filter(i -> unitId == null || i.getUnitId().equals(unitId))
                .filter(i -> tenantId == null || i.getTenantId().equals(tenantId))
                .filter(i -> month == null || i.getBillingMonth().equals(month.atDay(1)))
                .filter(i -> status == null || i.getStatus() == status)
                .toList();
        return summarize(views(list).stream()
                .sorted(Comparator.comparingLong(InvoiceView::daysOverdue).reversed()
                        .thenComparing(InvoiceView::dueAmount, Comparator.reverseOrder()))
                .toList());
    }

    /** All invoices of one tenant (tenant portal). */
    @Transactional(readOnly = true)
    public InvoiceList forTenant(UUID tenantId) {
        return summarize(views(invoices.findByTenantIdOrderByBillingMonthDesc(tenantId)));
    }

    // ---------------------------------------------------------------- Commands

    /**
     * Creates invoices for {@code month} for every current agreement covering that month that does not
     * already have one. {@code system} skips the caller checks (scheduled job).
     */
    @Transactional
    public GenerateResult generate(YearMonth month, boolean system) {
        Set<UUID> scope = null;
        if (!system) {
            guard.requireStaff();
            scope = guard.propertyScope();
        }
        String currency = orgs.current().getCurrency();
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        int created = 0;
        for (Agreement a : agreements.findByStatusIn(Agreement.CURRENT)) {
            if (!AccessGuard.inScope(scope, a.getPropertyId())) {
                continue;
            }
            boolean covers = !a.getStartDate().isAfter(last) && !a.getEndDate().isBefore(first);
            if (!covers || invoices.existsByAgreementIdAndBillingMonthAndStatusNot(a.getId(), first,
                    RentInvoice.Status.CANCELLED)) {
                continue;
            }
            RentInvoice inv = new RentInvoice();
            inv.setCode(codes.next("INV", 1, 0));
            inv.setTenantId(a.getTenantId());
            inv.setAgreementId(a.getId());
            inv.setUnitId(a.getUnitId());
            inv.setPropertyId(a.getPropertyId());
            inv.setBillingMonth(first);
            inv.setDueDate(month.atDay(Math.min(Math.max(a.getDueDay(), 1), 28)));
            inv.setRentAmount(a.getMonthlyRent());
            inv.setServiceCharge(a.getServiceCharge());
            inv.setUtilityCharge(a.getUtilityCharge());
            inv.setOtherCharge(a.getOtherCharge());
            invoices.save(inv);
            created++;
            notifications.notifyTenant(a.getTenantId(), "Rent generated",
                    "Rent for " + MONTH.format(month) + ": " + Money.format(inv.totalAmount(), currency)
                            + ", due " + DATE.format(inv.getDueDate()) + ".", null);
        }
        if (created > 0) {
            audit.log("Generated Rent", "Rent", month.toString(),
                    created + " invoice(s)" + (system ? " (automatic)" : ""));
        }
        refreshStatuses();
        return new GenerateResult(month, created);
    }

    @Transactional
    public InvoiceDetail cancel(UUID id, String reason) {
        RentInvoice i = load(id);
        if (!i.getStatus().isOpen()) {
            throw new BusinessException("Invoice is " + i.getStatus().name().toLowerCase());
        }
        if (i.getPaidAmount().signum() > 0) {
            throw BusinessException.conflict("Invoice has payments. Void the payments first.");
        }
        i.setStatus(RentInvoice.Status.CANCELLED);
        i.setCancelReason(reason.trim());
        audit.log("Cancelled Invoice", "Rent", i.getCode(), reason);
        return detail(i);
    }

    /** Recomputes paid amount and status from the invoice's non-voided payments. */
    @Transactional
    public void recalculate(RentInvoice i) {
        if (i.getStatus() == RentInvoice.Status.CANCELLED) {
            return;
        }
        payments.flush();
        BigDecimal paid = payments.findByInvoiceIdOrderByPaymentDateAscCreatedAtAsc(i.getId()).stream()
                .filter(p -> !p.isVoided()).map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        i.setPaidAmount(Money.of(paid));
        i.setStatus(statusFor(i, clock.today()));
    }

    /**
     * Flags overdue invoices and sends alerts, each at most once per invoice and user (dedup keys):
     * a reminder to the tenant and the property's staff when rent falls due within 3 days, and an
     * overdue alert to both once the due date has passed.
     */
    @Transactional
    public void refreshStatuses() {
        LocalDate today = clock.today();
        String currency = orgs.current().getCurrency();
        List<RentInvoice> open = invoices.findByStatusIn(RentInvoice.Status.OPEN);
        Names n = null;
        for (RentInvoice i : open) {
            i.setStatus(statusFor(i, today));
            if (!i.getStatus().isOpen()) {
                continue;
            }
            String month = MONTH.format(i.getBillingMonth());
            String due = Money.format(i.dueAmount(), currency);
            boolean overdue = i.getStatus() == RentInvoice.Status.OVERDUE;
            boolean dueSoon = !i.getDueDate().isBefore(today) && !i.getDueDate().isAfter(today.plusDays(3));
            if (!overdue && !dueSoon) {
                continue;
            }
            if (n == null) {
                n = names.load(open.stream().map(RentInvoice::getTenantId).collect(Collectors.toSet()),
                        open.stream().map(RentInvoice::getUnitId).collect(Collectors.toSet()), Set.of());
            }
            String who = n.tenant(i.getTenantId()) + ", unit " + n.unit(i.getUnitId());
            if (overdue) {
                notifications.notifyTenant(i.getTenantId(), "Payment overdue",
                        "Rent for " + month + " (" + due + ") was due on " + DATE.format(i.getDueDate()) + ".",
                        "overdue:" + i.getId());
                notifications.notifyStaff(i.getPropertyId(), "Overdue rent",
                        "Invoice " + i.getCode() + " (" + who + "): " + due + " for " + month + " is overdue.",
                        "overdue:" + i.getId());
            } else {
                notifications.notifyTenant(i.getTenantId(), "Rent due",
                        "Rent for " + month + " (" + due + ") is due on " + DATE.format(i.getDueDate()) + ".",
                        "due:" + i.getId());
                notifications.notifyStaff(i.getPropertyId(), "Rent due soon",
                        "Invoice " + i.getCode() + " (" + who + "): " + due + " for " + month + " is due on "
                                + DATE.format(i.getDueDate()) + ".",
                        "due:" + i.getId());
            }
        }
    }

    // ---------------------------------------------------------------- Helpers

    public RentInvoice load(UUID id) {
        guard.requireStaff();
        RentInvoice i = AccessGuard.owned(invoices.findById(id), "Invoice");
        guard.checkProperty(i.getPropertyId());
        return i;
    }

    private static RentInvoice.Status statusFor(RentInvoice i, LocalDate today) {
        if (i.getPaidAmount().compareTo(i.totalAmount()) >= 0) {
            return RentInvoice.Status.PAID;
        }
        if (i.getDueDate().isBefore(today)) {
            return RentInvoice.Status.OVERDUE;
        }
        return i.getPaidAmount().signum() > 0 ? RentInvoice.Status.PARTIALLY_PAID : RentInvoice.Status.PENDING;
    }

    List<InvoiceView> views(List<RentInvoice> list) {
        Names n = names.load(list.stream().map(RentInvoice::getTenantId).collect(Collectors.toSet()),
                list.stream().map(RentInvoice::getUnitId).collect(Collectors.toSet()),
                list.stream().map(RentInvoice::getPropertyId).collect(Collectors.toSet()));
        LocalDate today = clock.today();
        return list.stream().map(i -> new InvoiceView(i.getId(), i.getCode(), i.getTenantId(), n.tenant(i.getTenantId()),
                i.getAgreementId(), i.getUnitId(), n.unit(i.getUnitId()), i.getPropertyId(),
                n.property(i.getPropertyId()), i.getBillingMonth(), i.getDueDate(), i.getRentAmount(),
                i.getServiceCharge(), i.getUtilityCharge(), i.getOtherCharge(), i.totalAmount(), i.getPaidAmount(),
                i.dueAmount(), i.daysOverdue(today), i.getStatus(), i.getCancelReason())).toList();
    }

    private static InvoiceList summarize(List<InvoiceView> views) {
        List<InvoiceView> active = views.stream().filter(v -> v.status() != RentInvoice.Status.CANCELLED).toList();
        return new InvoiceList(
                Money.of(active.stream().map(InvoiceView::totalAmount).reduce(BigDecimal.ZERO, BigDecimal::add)),
                Money.of(active.stream().map(InvoiceView::paidAmount).reduce(BigDecimal.ZERO, BigDecimal::add)),
                Money.of(active.stream().map(InvoiceView::dueAmount).reduce(BigDecimal.ZERO, BigDecimal::add)),
                views);
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
