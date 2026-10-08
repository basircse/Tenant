package com.revesoft.tms.push;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushTokenRepository extends JpaRepository<PushToken, UUID> {

    Optional<PushToken> findByToken(String token);

    List<PushToken> findByUserId(UUID userId);

    @Modifying
    @Query("delete from PushToken t where t.token in :tokens")
    int deleteByTokenIn(@Param("tokens") Collection<String> tokens);
}
