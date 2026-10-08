package com.revesoft.tms.rent;

import com.revesoft.tms.rent.RentService.CancelRequest;
import com.revesoft.tms.rent.RentService.GenerateRequest;
import com.revesoft.tms.rent.RentService.GenerateResult;
import com.revesoft.tms.rent.RentService.InvoiceDetail;
import com.revesoft.tms.rent.RentService.InvoiceList;
import jakarta.validation.Valid;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class RentController {

    private final RentService service;

    public RentController(RentService service) {
        this.service = service;
    }

    /** {@code month} format: 2026-10. Omit for all months. */
    @GetMapping("/api/rents")
    public InvoiceList list(@RequestParam(required = false) YearMonth month,
                            @RequestParam(required = false) UUID propertyId,
                            @RequestParam(required = false) RentInvoice.Status status,
                            @RequestParam(required = false) UUID tenantId,
                            @RequestParam(required = false) String q) {
        return service.list(month, propertyId, status, tenantId, q);
    }

    @GetMapping("/api/rents/{id}")
    public InvoiceDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/api/rents/generate")
    public GenerateResult generate(@Valid @RequestBody GenerateRequest request) {
        return service.generate(request.month(), false);
    }

    @PostMapping("/api/rents/{id}/cancel")
    public InvoiceDetail cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        return service.cancel(id, request.reason());
    }

    @GetMapping("/api/dues")
    public InvoiceList dues(@RequestParam(required = false) UUID propertyId,
                            @RequestParam(required = false) UUID unitId,
                            @RequestParam(required = false) UUID tenantId,
                            @RequestParam(required = false) YearMonth month,
                            @RequestParam(required = false) RentInvoice.Status status) {
        return service.dues(propertyId, unitId, tenantId, month, status);
    }
}
