package com.revesoft.tms.expense;

import com.revesoft.tms.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "expenses")
public class Expense extends BaseEntity {

    public static final java.util.List<String> CATEGORIES = java.util.List.of(
            "Repair & Maintenance", "Utility", "Salary", "Tax", "Cleaning", "Security", "Other");

    /** Null = general expense across all properties. */
    private UUID propertyId;

    @Column(nullable = false)
    private LocalDate expenseDate;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private BigDecimal amount;

    private String description;
}
