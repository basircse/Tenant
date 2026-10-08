package com.revesoft.tms.property;

import com.revesoft.tms.common.BaseEntity;
import com.revesoft.tms.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "units")
public class Unit extends BaseEntity {

    public enum Status { AVAILABLE, OCCUPIED, RESERVED, MAINTENANCE, INACTIVE }

    @Column(nullable = false, updatable = false)
    private UUID propertyId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String floor;

    @Column(nullable = false)
    private String unitNo;

    private String unitType;
    private String size;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.AVAILABLE;

    public BigDecimal totalMonthly() {
        return Money.sum(monthlyRent, serviceCharge, utilityCharge, otherCharge);
    }
}
