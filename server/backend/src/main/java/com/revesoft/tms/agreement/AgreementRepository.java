package com.revesoft.tms.agreement;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgreementRepository extends JpaRepository<Agreement, UUID> {

    List<Agreement> findByStatusIn(Collection<Agreement.Status> statuses);

    List<Agreement> findByPropertyIdAndStatusIn(UUID propertyId, Collection<Agreement.Status> statuses);

    Optional<Agreement> findByTenantIdAndStatusIn(UUID tenantId, Collection<Agreement.Status> statuses);

    Optional<Agreement> findByUnitIdAndStatusIn(UUID unitId, Collection<Agreement.Status> statuses);

    List<Agreement> findByTenantId(UUID tenantId);

    boolean existsByUnitId(UUID unitId);
}
