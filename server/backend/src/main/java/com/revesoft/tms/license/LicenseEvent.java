package com.revesoft.tms.license;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** History of licensing actions for an organisation. Managed by the vendor, so not org-scoped. */
@Getter
@Setter
@Entity
@Table(name = "license_events")
public class LicenseEvent {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, updatable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String action;

    private String details;
    private Instant expiresBefore;
    private Instant expiresAfter;

    @Column(nullable = false)
    private String actorName;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
