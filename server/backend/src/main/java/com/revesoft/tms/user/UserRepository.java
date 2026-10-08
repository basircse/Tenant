package com.revesoft.tms.user;

import com.revesoft.tms.security.Role;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByUsernameIgnoreCase(String username);

    List<AppUser> findByEmailIgnoreCase(String email);

    List<AppUser> findByMobile(String mobile);

    boolean existsByUsernameIgnoreCase(String username);

    Optional<AppUser> findByTenantId(UUID tenantId);

    List<AppUser> findByRoleInAndStatus(Collection<Role> roles, AppUser.Status status);

    List<AppUser> findAllByOrderByRoleAscNameAsc();

    /** Vendor (ROOT) query. */
    List<AppUser> findByOrgIdAndRole(UUID orgId, Role role);
}
