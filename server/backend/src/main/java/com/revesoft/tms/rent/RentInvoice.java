package com.revesoft.tms.rent;

import com.revesoft.tms.common.BaseEntity;
import com.revesoft.tms.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "rent_invoices")
public class RentInvoice extends BaseEntity {

    public enum Status {
        PENDING, PARTIALLY_PAID, PAID, OVERDUE, CANCELLED;

        /** Still collectable. */
        public boolean isOpen() {
            return this == PENDING || this == PARTIALLY_PAID || this == OVERDUE;
        }

        public static final List<Status> OPEN = List.of(PENDING, PARTIALLY_PAID, OVERDUE);
    }

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID agreementId;

    @Column(nullable = false)
    private UUID unitId;

    @Column(nullable = false)
    private UUID propertyId;

    /** First day of the billed month. */
    @Column(nullable = false)
    private LocalDate billingMonth;

    @Column(nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false)
    private BigDecimal rentAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal serviceCharge = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal utilityCharge = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal otherCharge = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    private String cancelReason;

    public BigDecimal totalAmount() {
        return Money.sum(rentAmount, serviceCharge, utilityCharge, otherCharge);
    }

    public BigDecimal dueAmount() {
        if (status == Status.CANCELLED) {
            return BigDecimal.ZERO;
        }
        BigDecimal due = totalAmount().subtract(paidAmount);
        return due.signum() < 0 ? BigDecimal.ZERO.setScale(2) : Money.of(due);
    }

    public long daysOverdue(LocalDate today) {
        if (!status.isOpen() || !today.isAfter(dueDate)) {
            return 0;
        }
        return ChronoUnit.DAYS.between(dueDate, today);
    }
}
