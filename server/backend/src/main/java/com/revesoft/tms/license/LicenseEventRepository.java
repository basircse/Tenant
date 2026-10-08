package com.revesoft.tms.license;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LicenseEventRepository extends JpaRepository<LicenseEvent, UUID> {

    List<LicenseEvent> findByOrgIdOrderByCreatedAtDesc(UUID orgId);
}
