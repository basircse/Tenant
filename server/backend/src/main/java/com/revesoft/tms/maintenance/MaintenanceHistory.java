package com.revesoft.tms.maintenance;

import com.revesoft.tms.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "maintenance_history")
public class MaintenanceHistory extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MaintenanceRequest.Status status;

    @Column(nullable = false)
    private String changedBy;

    private String note;
}
