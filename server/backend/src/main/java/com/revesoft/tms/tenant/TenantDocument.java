package com.revesoft.tms.tenant;

import com.revesoft.tms.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A tenant document: a reference (location/link) and, optionally, an uploaded file. */
@Getter
@Setter
@Entity
@Table(name = "tenant_documents")
public class TenantDocument extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String docType;

    @Column(nullable = false)
    private String reference;

    /** Storage key of the uploaded file (see FileStorage); null for reference-only documents. */
    private String fileKey;
    private String contentType;
    private Long sizeBytes;
    private String originalName;
}
