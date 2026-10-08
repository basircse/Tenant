package com.revesoft.tms.maintenance;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceHistoryRepository extends JpaRepository<MaintenanceHistory, UUID> {

    List<MaintenanceHistory> findByRequestIdOrderByCreatedAtAsc(UUID requestId);
}
