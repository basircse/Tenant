package com.revesoft.tms.license;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    Optional<Device> findByAppAndDeviceKey(Device.App app, String deviceKey);

    long countByAppAndStatus(Device.App app, Device.Status status);

    List<Device> findByAppOrderByLastSeenAtDesc(Device.App app);

    /** Vendor (ROOT) queries across organisations. */
    List<Device> findByOrgIdOrderByLastSeenAtDesc(UUID orgId);

    List<Device> findByAppAndStatus(Device.App app, Device.Status status);
}
