package com.revesoft.tms.org;

import com.revesoft.tms.org.OrganizationService.OrgView;
import com.revesoft.tms.org.OrganizationService.SettingsRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/org")
public class OrganizationController {

    private final OrganizationService service;

    public OrganizationController(OrganizationService service) {
        this.service = service;
    }

    @GetMapping
    public OrgView get() {
        return service.view();
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public OrgView update(@Valid @RequestBody SettingsRequest request) {
        return service.updateSettings(request);
    }
}
