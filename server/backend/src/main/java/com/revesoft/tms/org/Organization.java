package com.revesoft.tms.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A landlord / property company: the unit of data isolation and licensing. */
@Getter
@Setter
@Entity
@Table(name = "organizations")
public class Organization {

    public enum Status { PENDING, ACTIVE, SUSPENDED, REJECTED }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    private String name;

    private String contactName;
    private String phone;
    private String email;
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    /** End of the paid licence period; null = no expiry. */
    private Instant licenseExpiresAt;
    /** Null = unlimited. */
    private Integer maxUnits;
    /** Admin-app devices; null = unlimited. */
    private Integer maxDevices;
    private String plan;
    private String licenseNote;
    private Instant approvedAt;

    /** The software vendor's own organisation (holds vendor users only). */
    @Column(name = "is_vendor", nullable = false)
    private boolean vendor;

    @Column(nullable = false)
    private String currency = "৳";

    @Column(nullable = false)
    private int expiryAlertDays = 30;

    @Column(nullable = false)
    private boolean autoGenerateRent = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
