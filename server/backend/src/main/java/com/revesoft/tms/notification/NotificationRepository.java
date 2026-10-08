package com.revesoft.tms.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<AppNotification, UUID> {

    List<AppNotification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable page);

    long countByUserIdAndReadFalse(UUID userId);

    boolean existsByUserIdAndDedupKey(UUID userId, String dedupKey);

    @Modifying
    @Query("update AppNotification n set n.read = true where n.userId = :userId and n.read = false")
    int markAllRead(@Param("userId") UUID userId);
}
