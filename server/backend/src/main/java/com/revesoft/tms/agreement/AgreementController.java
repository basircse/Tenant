package com.revesoft.tms.agreement;

import com.revesoft.tms.agreement.AgreementService.AgreementRequest;
import com.revesoft.tms.agreement.AgreementService.AgreementView;
import com.revesoft.tms.agreement.AgreementService.RenewRequest;
import com.revesoft.tms.agreement.AgreementService.TerminateRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agreements")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class AgreementController {

    private final AgreementService service;

    public AgreementController(AgreementService service) {
        this.service = service;
    }

    @GetMapping
    public List<AgreementView> list(@RequestParam(required = false) Agreement.Status status,
                                    @RequestParam(required = false) UUID propertyId,
                                    @RequestParam(required = false) UUID tenantId,
                                    @RequestParam(required = false) String q) {
        return service.list(status, propertyId, tenantId, q);
    }

    @GetMapping("/{id}")
    public AgreementView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgreementView create(@Valid @RequestBody AgreementRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public AgreementView updateDraft(@PathVariable UUID id, @Valid @RequestBody AgreementRequest request) {
        return service.updateDraft(id, request);
    }

    @PostMapping("/{id}/activate")
    public AgreementView activate(@PathVariable UUID id) {
        return service.activate(id);
    }

    @PostMapping("/{id}/terminate")
    public AgreementView terminate(@PathVariable UUID id, @Valid @RequestBody TerminateRequest request) {
        return service.terminate(id, request);
    }

    @PostMapping("/{id}/renew")
    public AgreementView renew(@PathVariable UUID id, @Valid @RequestBody RenewRequest request) {
        return service.renew(id, request);
    }
}
