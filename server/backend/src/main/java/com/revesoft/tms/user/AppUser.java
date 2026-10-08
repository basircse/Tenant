package com.revesoft.tms.user;

import com.revesoft.tms.common.BaseEntity;
import com.revesoft.tms.security.Role;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "users")
public class AppUser extends BaseEntity {

    /** BLOCKED: stopped by an admin or the vendor; see the blocked* fields. */
    public enum Status { ACTIVE, INACTIVE, LOCKED, BLOCKED }

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String username;

    private String email;
    private String mobile;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    /** For TENANT users: the linked tenant record. */
    private UUID tenantId;

    @Column(nullable = false)
    private int failedAttempts;

    @Column(nullable = false)
    private boolean mustChangePassword;

    /** For MANAGER users: properties they may manage. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_properties", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "property_id")
    private Set<UUID> propertyIds = new HashSet<>();

    /** Shown to the user when they try to sign in. */
    private String blockedReason;

    /** Name of the admin or vendor who blocked the user. */
    private String blockedBy;

    private Instant blockedAt;

    /** Blocked by the vendor: the organisation's admins cannot lift it. */
    @Column(nullable = false)
    private boolean blockedByVendor;
}
