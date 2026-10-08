package com.revesoft.tms.tenant;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantDocumentRepository extends JpaRepository<TenantDocument, UUID> {

    List<TenantDocument> findByTenantIdOrderByCreatedAtAsc(UUID tenantId);
}
