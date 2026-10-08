package com.revesoft.tms.payment;

import com.revesoft.tms.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {

    public enum Method { CASH, BANK_TRANSFER, BKASH, NAGAD, OTHER }

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false, updatable = false)
    private String receiptNo;

    @Column(nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID unitId;

    @Column(nullable = false, updatable = false)
    private UUID propertyId;

    @Column(nullable = false)
    private LocalDate paymentDate;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Method method;

    private String referenceNo;
    private String remarks;

    @Column(nullable = false)
    private String recordedBy;

    @Column(nullable = false)
    private boolean voided;

    private String voidReason;
    private Instant voidedAt;
}
