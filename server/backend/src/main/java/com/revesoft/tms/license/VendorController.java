package com.revesoft.tms.license;

import com.revesoft.tms.license.VendorService.ApproveRequest;
import com.revesoft.tms.license.VendorService.DeviceAdminView;
import com.revesoft.tms.license.VendorService.ExtendRequest;
import com.revesoft.tms.license.VendorService.LimitsRequest;
import com.revesoft.tms.license.VendorService.NoteRequest;
import com.revesoft.tms.license.VendorService.OrgDetail;
import com.revesoft.tms.license.VendorService.OrgSummary;
import com.revesoft.tms.license.VendorService.ReasonRequest;
import com.revesoft.tms.license.VendorService.Summary;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Vendor console API. Only users with the VENDOR role. */
@RestController
@RequestMapping("/api/vendor")
@PreAuthorize("hasRole('VENDOR')")
public class VendorController {

    private final VendorService service;

    public VendorController(VendorService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public Summary summary() {
        return service.summary();
    }

    @GetMapping("/organizations")
    public List<OrgSummary> list(@RequestParam(required = false) LicenseState state,
                                 @RequestParam(required = false) String q) {
        return service.list(state, q);
    }

    @GetMapping("/organizations/{id}")
    public OrgDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/organizations/{id}/approve")
    public OrgDetail approve(@PathVariable UUID id, @Valid @RequestBody ApproveRequest request) {
        return service.approve(id, request);
    }

    @PostMapping("/organizations/{id}/reject")
    public OrgDetail reject(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return service.reject(id, request.reason());
    }

    @PostMapping("/organizations/{id}/extend")
    public OrgDetail extend(@PathVariable UUID id, @Valid @RequestBody ExtendRequest request) {
        return service.extend(id, request);
    }

    @PutMapping("/organizations/{id}/limits")
    public OrgDetail limits(@PathVariable UUID id, @Valid @RequestBody LimitsRequest request) {
        return service.updateLimits(id, request);
    }

    @PostMapping("/organizations/{id}/suspend")
    public OrgDetail suspend(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return service.suspend(id, request.reason());
    }

    @PostMapping("/organizations/{id}/reactivate")
    public OrgDetail reactivate(@PathVariable UUID id, @RequestBody(required = false) NoteRequest request) {
        return service.reactivate(id, request == null ? null : request.note());
    }

    @PostMapping("/devices/{deviceId}/approve")
    public DeviceAdminView approveDevice(@PathVariable UUID deviceId) {
        return service.approveDevice(deviceId);
    }

    @PostMapping("/devices/{deviceId}/block")
    public DeviceAdminView blockDevice(@PathVariable UUID deviceId) {
        return service.blockDevice(deviceId);
    }
}
