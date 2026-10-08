package com.revesoft.tms.license;

import com.revesoft.tms.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A phone/computer that signed in. Admin-app devices count against the licence's device limit. */
@Getter
@Setter
@Entity
@Table(name = "devices")
public class Device extends BaseEntity {

    public enum App { ADMIN, TENANT }

    public enum Status { APPROVED, PENDING, BLOCKED }

    /** Random install id generated and kept by the app. */
    @Column(nullable = false, updatable = false)
    private String deviceKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private App app;

    private String name;
    private String platform;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private UUID lastUserId;
    private Instant lastSeenAt;
}
