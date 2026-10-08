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
@Table(name = "maintenance_requests")
public class MaintenanceRequest extends BaseEntity {

    public enum Type { ELECTRICAL, PLUMBING, GAS, LIFT, AC, WATER, GENERAL }

    public enum Priority { LOW, MEDIUM, HIGH, URGENT }

    public enum Status {
        OPEN, ASSIGNED, IN_PROGRESS, COMPLETED, CLOSED;

        public boolean isPending() {
            return this != COMPLETED && this != CLOSED;
        }
    }

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID unitId;

    @Column(nullable = false, updatable = false)
    private UUID propertyId;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    /** Free-text attachment reference (link) sent with the request. */
    private String attachment;
    private String assignedTo;

    /** Uploaded photo / document (see FileStorage); one per request. */
    private String attachmentKey;
    private String attachmentType;
    private Long attachmentSize;
    private String attachmentName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;
}
