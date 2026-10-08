package com.revesoft.tms.maintenance;

import com.revesoft.tms.maintenance.MaintenanceService.AssignRequest;
import com.revesoft.tms.maintenance.MaintenanceService.CreateRequest;
import com.revesoft.tms.maintenance.MaintenanceService.RequestView;
import com.revesoft.tms.maintenance.MaintenanceService.StatusRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/maintenance")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class MaintenanceController {

    private final MaintenanceService service;

    public MaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    @GetMapping
    public List<RequestView> list(@RequestParam(required = false) MaintenanceRequest.Status status,
                                  @RequestParam(required = false) MaintenanceRequest.Priority priority,
                                  @RequestParam(required = false) UUID propertyId,
                                  @RequestParam(defaultValue = "false") boolean pendingOnly,
                                  @RequestParam(required = false) String q) {
        return service.list(status, priority, propertyId, pendingOnly, q);
    }

    @GetMapping("/{id}")
    public RequestView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RequestView create(@Valid @RequestBody CreateRequest request) {
        return service.create(request);
    }

    @PostMapping("/{id}/assign")
    public RequestView assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest request) {
        return service.assign(id, request.assignedTo());
    }

    /** Multipart upload with field {@code file}; replaces any earlier attachment. */
    @PostMapping(path = "/{id}/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RequestView attach(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        return service.attach(id, file);
    }

    @GetMapping("/{id}/attachment")
    public ResponseEntity<Resource> attachment(@PathVariable UUID id) {
        return service.attachment(id).toResponse();
    }

    @PostMapping("/{id}/status")
    public RequestView updateStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return service.updateStatus(id, request);
    }
}
