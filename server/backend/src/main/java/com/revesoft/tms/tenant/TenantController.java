package com.revesoft.tms.tenant;

import com.revesoft.tms.tenant.TenantService.DocumentRequest;
import com.revesoft.tms.tenant.TenantService.DocumentView;
import com.revesoft.tms.tenant.TenantService.TenantDetail;
import com.revesoft.tms.tenant.TenantService.TenantRequest;
import com.revesoft.tms.tenant.TenantService.TenantView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/tenants")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class TenantController {

    private final TenantService service;

    public TenantController(TenantService service) {
        this.service = service;
    }

    @GetMapping
    public List<TenantView> list(@RequestParam(required = false) String q,
                                 @RequestParam(required = false) Tenant.Status status,
                                 @RequestParam(required = false) UUID propertyId) {
        return service.list(q, status, propertyId);
    }

    @GetMapping("/{id}")
    public TenantDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantDetail create(@Valid @RequestBody TenantRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public TenantDetail update(@PathVariable UUID id, @Valid @RequestBody TenantRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentView addDocument(@PathVariable UUID id, @Valid @RequestBody DocumentRequest request) {
        return service.addDocument(id, request);
    }

    /** Multipart upload: {@code file} plus {@code name}, {@code docType} and optional {@code reference}. */
    @PostMapping(path = "/{id}/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentView uploadDocument(@PathVariable UUID id, @RequestParam("file") MultipartFile file,
                                       @RequestParam String name, @RequestParam String docType,
                                       @RequestParam(required = false) String reference) {
        return service.uploadDocument(id, name, docType, reference, file);
    }

    @GetMapping("/{id}/documents/{documentId}/file")
    public ResponseEntity<Resource> documentFile(@PathVariable UUID id, @PathVariable UUID documentId) {
        return service.documentFile(id, documentId).toResponse();
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeDocument(@PathVariable UUID id, @PathVariable UUID documentId) {
        service.removeDocument(id, documentId);
    }
}
