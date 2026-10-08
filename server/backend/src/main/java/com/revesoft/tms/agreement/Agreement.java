package com.revesoft.tms.agreement;

import com.revesoft.tms.common.BaseEntity;
import com.revesoft.tms.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "agreements")
public class Agreement extends BaseEntity {

    public enum Status {
        DRAFT, ACTIVE, EXPIRING, EXPIRED, TERMINATED;

        /** The tenant currently occupies the unit under this agreement. */
        public boolean isCurrent() {
            return this == ACTIVE || this == EXPIRING;
        }
    }

    public static final List<Status> CURRENT = List.of(Status.ACTIVE, Status.EXPIRING);

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID unitId;

    @Column(nullable = false)
    private UUID propertyId;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private BigDecimal monthlyRent = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal serviceCharge = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal utilityCharge = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal otherCharge = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal securityDeposit = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal advanceAmount = BigDecimal.ZERO;

    /** Day of month (1-28) rent is due. */
    @Column(nullable = false)
    private int dueDay = 5;

    private String documentRef;
    private String terms;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    private UUID renewedFromId;
    private LocalDate terminatedOn;
    private String terminationReason;

    public BigDecimal totalMonthly() {
        return Money.sum(monthlyRent, serviceCharge, utilityCharge, otherCharge);
    }
}
