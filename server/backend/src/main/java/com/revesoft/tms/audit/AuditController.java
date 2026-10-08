package com.revesoft.tms.audit;

import com.revesoft.tms.audit.AuditService.AuditView;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    public List<AuditView> search(@RequestParam(required = false) String entityType,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "200") int limit) {
        return service.search(entityType, q, limit);
    }
}
