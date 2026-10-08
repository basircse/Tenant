package com.revesoft.tms.payment;

import com.revesoft.tms.audit.AuditService;
import com.revesoft.tms.common.BusinessClock;
import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.common.CodeGenerator;
import com.revesoft.tms.common.Money;
import com.revesoft.tms.common.NameLookup;
import com.revesoft.tms.common.NameLookup.Names;
import com.revesoft.tms.notification.NotificationService;
import com.revesoft.tms.org.Organization;
import com.revesoft.tms.org.OrganizationService;
import com.revesoft.tms.rent.RentInvoice;
import com.revesoft.tms.rent.RentInvoiceRepository;
import com.revesoft.tms.rent.RentService;
import com.revesoft.tms.security.AccessGuard;
import com.revesoft.tms.security.AuthUser;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Payment collection, receipts and voiding (SRS §11). */
@Service
public class PaymentService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final PaymentRepository payments;
    private final RentInvoiceRepository invoices;
    private final RentService rent;
    private final OrganizationService orgs;
    private final NameLookup names;
    private final CodeGenerator codes;
    private final AccessGuard guard;
    private final AuditService audit;
    private final NotificationService notifications;
    private final BusinessClock clock;

    public PaymentService(PaymentRepository payments, RentInvoiceRepository invoices, RentService rent,
                          OrganizationService orgs, NameLookup names, CodeGenerator codes, AccessGuard guard,
                          AuditService audit, NotificationService notifications, BusinessClock clock) {
        this.payments = payments;
        this.invoices = invoices;
        this.rent = rent;
        this.orgs = orgs;
        this.names = names;
        this.codes = codes;
        this.guard = guard;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- DTOs

    public record PaymentRequest(@NotNull UUID invoiceId,
                                 @NotNull @DecimalMin(value = "0.01", message = "must be greater than zero") BigDecimal amount,
                                 LocalDate paymentDate,
                                 @NotNull Payment.Method method,
                                 String referenceNo,
                                 String remarks) {
    }

    public record VoidRequest(@NotBlank String reason) {
    }

    public record PaymentView(UUID id, String code, String receiptNo, UUID invoiceId, String invoiceCode,
                              LocalDate billingMonth, UUID tenantId, String tenantName, UUID unitId, String unitNo,
                              UUID propertyId, String propertyName, LocalDate paymentDate, BigDecimal amount,
                              Payment.Method method, String referenceNo, String remarks, String recordedBy,
                              boolean voided, String voidReason) {
    }

    public record PaymentList(BigDecimal total, List<PaymentView> items) {
    }

    /** Data for printing / sharing a money receipt. */
    public record Receipt(String organization, String receiptNo, LocalDate date, String tenantName, String tenantCode,
                          String propertyName, String unitNo, String invoiceCode, String billingMonth,
                          BigDecimal invoiceTotal, BigDecimal amountPaid, Payment.Method method,
                          String referenceNo, BigDecimal balanceAfter, String remarks, String receivedBy,
                          boolean voided, String voidReason, String currency, String text) {
    }

    // ---------------------------------------------------------------- Queries

    @Transactional(readOnly = true)
    public PaymentList list(LocalDate from, LocalDate to, Payment.Method method, UUID propertyId, UUID tenantId,
                            boolean includeVoided) {
        guard.requireStaff();
        Set<UUID> scope = guard.propertyScope();
        return summarize(views(payments.findAllByOrderByPaymentDateDescCreatedAtDesc().stream()
                .filter(p -> AccessGuard.inScope(scope, p.getPropertyId()))
                .filter(p -> includeVoided || !p.isVoided())
                .filter(p -> from == null || !p.getPaymentDate().isBefore(from))
                .filter(p -> to == null || !p.getPaymentDate().isAfter(to))
                .filter(p -> method == null || p.getMethod() == method)
                .filter(p -> propertyId == null || p.getPropertyId().equals(propertyId))
                .filter(p -> tenantId == null || p.getTenantId().equals(tenantId))
                .toList()));
    }

    @Transactional(readOnly = true)
    public PaymentView get(UUID id) {
        return views(List.of(load(id))).get(0);
    }

    @Transactional(readOnly = true)
    public Receipt receipt(UUID id) {
        return receiptOf(load(id));
    }

    /** Payments of one tenant (tenant portal). */
    @Transactional(readOnly = true)
    public PaymentList forTenant(UUID tenantId) {
        return summarize(views(payments.findByTenantIdOrderByPaymentDateDescCreatedAtDesc(tenantId)));
    }

    /** Receipt for a tenant's own payment (tenant portal). */
    @Transactional(readOnly = true)
    public Receipt receiptForTenant(UUID tenantId, UUID paymentId) {
        Payment p = AccessGuard.owned(payments.findById(paymentId), "Payment");
        if (!p.getTenantId().equals(tenantId)) {
            throw BusinessException.notFound("Payment");
        }
        return receiptOf(p);
    }

    // ---------------------------------------------------------------- Commands

    @Transactional
    public Receipt record(PaymentRequest r) {
        AuthUser me = guard.requireStaff();
        RentInvoice inv = AccessGuard.owned(invoices.findById(r.invoiceId()), "Invoice");
        guard.checkProperty(inv.getPropertyId());
        if (!inv.getStatus().isOpen()) {
            throw new BusinessException("Invoice is " + inv.getStatus().name().toLowerCase().replace('_', ' '));
        }
        BigDecimal amount = Money.of(r.amount());
        Organization org = orgs.current();
        if (amount.compareTo(inv.dueAmount()) > 0) {
            throw new BusinessException("Amount exceeds the outstanding balance ("
                    + Money.format(inv.dueAmount(), org.getCurrency()) + ")");
        }
        if (r.method() != Payment.Method.CASH && (r.referenceNo() == null || r.referenceNo().isBlank())) {
            throw new BusinessException("Transaction/reference number is required for " + r.method().name());
        }
        LocalDate date = r.paymentDate() == null ? clock.today() : r.paymentDate();
        if (date.isAfter(clock.today())) {
            throw new BusinessException("Payment date cannot be in the future");
        }
        Payment p = new Payment();
        p.setCode(codes.next("PAY"));
        p.setReceiptNo(codes.next("RCPT", 1, 6));
        p.setInvoiceId(inv.getId());
        p.setTenantId(inv.getTenantId());
        p.setUnitId(inv.getUnitId());
        p.setPropertyId(inv.getPropertyId());
        p.setPaymentDate(date);
        p.setAmount(amount);
        p.setMethod(r.method());
        p.setReferenceNo(r.referenceNo() == null ? null : r.referenceNo().trim());
        p.setRemarks(r.remarks());
        p.setRecordedBy(me.name());
        payments.save(p);
        rent.recalculate(inv);
        audit.log("Payment Entry", "Payment", p.getCode(),
                Money.format(amount, org.getCurrency()) + " for " + inv.getCode() + " (" + p.getReceiptNo() + ")");
        notifications.notifyTenant(inv.getTenantId(), "Payment received",
                "We received " + Money.format(amount, org.getCurrency()) + " (" + p.getReceiptNo() + "). Outstanding for "
                        + MONTH.format(inv.getBillingMonth()) + ": " + Money.format(inv.dueAmount(), org.getCurrency()) + ".",
                null);
        return receiptOf(p);
    }

    @Transactional
    public PaymentView voidPayment(UUID id, String reason) {
        guard.requireAdmin();
        Payment p = load(id);
        if (p.isVoided()) {
            throw new BusinessException("Payment is already voided");
        }
        p.setVoided(true);
        p.setVoidReason(reason.trim());
        p.setVoidedAt(Instant.now());
        invoices.findById(p.getInvoiceId()).ifPresent(rent::recalculate);
        audit.log("Payment Modification", "Payment", p.getCode(),
                "Voided " + Money.format(p.getAmount(), orgs.current().getCurrency()) + ": " + reason);
        return get(id);
    }

    // ---------------------------------------------------------------- Helpers

    private Payment load(UUID id) {
        guard.requireStaff();
        Payment p = AccessGuard.owned(payments.findById(id), "Payment");
        guard.checkProperty(p.getPropertyId());
        return p;
    }

    private Receipt receiptOf(Payment p) {
        Organization org = orgs.current();
        RentInvoice inv = invoices.findById(p.getInvoiceId()).orElseThrow();
        Names n = names.load(Set.of(p.getTenantId()), Set.of(p.getUnitId()), Set.of(p.getPropertyId()));
        String cur = org.getCurrency();
        String month = MONTH.format(inv.getBillingMonth());
        String tenantCode = n.tenants().containsKey(p.getTenantId()) ? n.tenants().get(p.getTenantId()).getCode() : "";
        StringBuilder t = new StringBuilder()
                .append(org.getName().toUpperCase()).append('\n')
                .append("MONEY RECEIPT\n")
                .append("-".repeat(36)).append('\n')
                .append("Receipt No : ").append(p.getReceiptNo()).append('\n')
                .append("Date       : ").append(p.getPaymentDate()).append('\n')
                .append("Tenant     : ").append(n.tenant(p.getTenantId())).append(" (").append(tenantCode).append(")\n")
                .append("Unit       : ").append(n.property(p.getPropertyId())).append(" • ").append(n.unit(p.getUnitId())).append('\n')
                .append("Invoice    : ").append(inv.getCode()).append(" (").append(month).append(")\n")
                .append("-".repeat(36)).append('\n')
                .append("Amount Paid: ").append(Money.format(p.getAmount(), cur)).append('\n')
                .append("Method     : ").append(p.getMethod()).append('\n');
        if (p.getReferenceNo() != null && !p.getReferenceNo().isBlank()) {
            t.append("Reference  : ").append(p.getReferenceNo()).append('\n');
        }
        t.append("Balance    : ").append(Money.format(inv.dueAmount(), cur)).append('\n')
                .append("Received by: ").append(p.getRecordedBy()).append('\n');
        if (p.isVoided()) {
            t.append("*** VOIDED: ").append(p.getVoidReason()).append(" ***\n");
        }
        return new Receipt(org.getName(), p.getReceiptNo(), p.getPaymentDate(), n.tenant(p.getTenantId()), tenantCode,
                n.property(p.getPropertyId()), n.unit(p.getUnitId()), inv.getCode(), month, inv.totalAmount(),
                p.getAmount(), p.getMethod(), p.getReferenceNo(), inv.dueAmount(), p.getRemarks(), p.getRecordedBy(),
                p.isVoided(), p.getVoidReason(), cur, t.toString());
    }

    private List<PaymentView> views(List<Payment> list) {
        Names n = names.load(list.stream().map(Payment::getTenantId).collect(Collectors.toSet()),
                list.stream().map(Payment::getUnitId).collect(Collectors.toSet()),
                list.stream().map(Payment::getPropertyId).collect(Collectors.toSet()));
        Map<UUID, RentInvoice> inv = invoices.findAllById(list.stream().map(Payment::getInvoiceId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(RentInvoice::getId, Function.identity()));
        return list.stream().map(p -> {
            RentInvoice i = inv.get(p.getInvoiceId());
            return new PaymentView(p.getId(), p.getCode(), p.getReceiptNo(), p.getInvoiceId(),
                    i == null ? null : i.getCode(), i == null ? null : i.getBillingMonth(), p.getTenantId(),
                    n.tenant(p.getTenantId()), p.getUnitId(), n.unit(p.getUnitId()), p.getPropertyId(),
                    n.property(p.getPropertyId()), p.getPaymentDate(), p.getAmount(), p.getMethod(),
                    p.getReferenceNo(), p.getRemarks(), p.getRecordedBy(), p.isVoided(), p.getVoidReason());
        }).toList();
    }

    private static PaymentList summarize(List<PaymentView> views) {
        return new PaymentList(Money.of(views.stream().filter(v -> !v.voided()).map(PaymentView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)), views);
    }
}
