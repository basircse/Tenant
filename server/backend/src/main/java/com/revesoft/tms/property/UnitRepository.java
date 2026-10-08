package com.revesoft.tms.property;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnitRepository extends JpaRepository<Unit, UUID> {

    List<Unit> findByPropertyIdOrderByFloorAscUnitNoAsc(UUID propertyId);

    boolean existsByPropertyIdAndUnitNoIgnoreCaseAndIdNot(UUID propertyId, String unitNo, UUID id);

    long countByStatusNot(Unit.Status status);
}
