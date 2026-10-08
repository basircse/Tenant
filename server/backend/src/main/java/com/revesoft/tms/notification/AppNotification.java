package com.revesoft.tms.notification;

import com.revesoft.tms.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "notifications")
public class AppNotification extends BaseEntity {

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    /** When set, the same notification is never sent twice to a user (used by daily jobs). */
    private String dedupKey;

    @Column(name = "is_read", nullable = false)
    private boolean read;
}
