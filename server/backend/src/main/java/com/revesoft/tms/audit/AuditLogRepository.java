package com.revesoft.tms.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @Query("""
            select a from AuditLog a
            where (:entityType is null or a.entityType = :entityType)
              and (:q is null
                   or lower(a.action) like :q or lower(a.userName) like :q
                   or lower(a.entityId) like :q or lower(a.details) like :q)
            order by a.createdAt desc""")
    List<AuditLog> search(@Param("entityType") String entityType, @Param("q") String q, Pageable page);
}
