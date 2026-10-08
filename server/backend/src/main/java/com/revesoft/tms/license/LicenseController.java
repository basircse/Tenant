package com.revesoft.tms.license;

import com.revesoft.tms.license.LicenseService.DeviceView;
import com.revesoft.tms.license.LicenseService.LicenseStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Licence status and device management for the landlord's own organisation. */
@RestController
public class LicenseController {

    private final LicenseService service;

    public LicenseController(LicenseService service) {
        this.service = service;
    }

    public record RenewalRequest(String message) {
    }

    /** Always reachable (even when blocked) so the apps can show the reason. */
    @GetMapping("/api/license")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','TENANT')")
    public LicenseStatus status() {
        return service.status();
    }

    @PostMapping("/api/license/renewal-request")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestRenewal(@RequestBody(required = false) RenewalRequest request) {
        service.requestRenewal(request == null ? null : request.message());
    }

    @GetMapping("/api/devices")
    @PreAuthorize("hasRole('ADMIN')")
    public List<DeviceView> devices() {
        return service.myDevices();
    }

    @DeleteMapping("/api/devices/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeDevice(@PathVariable UUID id) {
        service.removeDevice(id);
    }
}
