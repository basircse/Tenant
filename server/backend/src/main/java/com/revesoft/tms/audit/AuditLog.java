package com.revesoft.tms.audit;

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
@Table(name = "audit_logs")
public class AuditLog extends BaseEntity {

    private UUID userId;

    @Column(nullable = false)
    private String userName;

    @Column(nullable = false)
    private String action;

    private String entityType;
    private String entityId;
    private String details;
}
