package com.revesoft.tms.tenant;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    List<Tenant> findAllByOrderByNameAsc();

    boolean existsByMobileAndIdNot(String mobile, UUID id);

    boolean existsByNidIgnoreCaseAndIdNot(String nid, UUID id);
}
