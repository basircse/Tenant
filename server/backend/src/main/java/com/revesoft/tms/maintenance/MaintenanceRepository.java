package com.revesoft.tms.maintenance;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceRepository extends JpaRepository<MaintenanceRequest, UUID> {

    List<MaintenanceRequest> findAllByOrderByCreatedAtDesc();

    List<MaintenanceRequest> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
