package com.revesoft.tms.payment;

import com.revesoft.tms.payment.PaymentService.PaymentList;
import com.revesoft.tms.payment.PaymentService.PaymentRequest;
import com.revesoft.tms.payment.PaymentService.PaymentView;
import com.revesoft.tms.payment.PaymentService.Receipt;
import com.revesoft.tms.payment.PaymentService.VoidRequest;
import com.revesoft.tms.report.ReceiptPdf;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/payments")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class PaymentController {

    private final PaymentService service;
    private final ReceiptPdf receiptPdf;

    public PaymentController(PaymentService service, ReceiptPdf receiptPdf) {
        this.service = service;
        this.receiptPdf = receiptPdf;
    }

    @GetMapping
    public PaymentList list(@RequestParam(required = false) LocalDate from,
                            @RequestParam(required = false) LocalDate to,
                            @RequestParam(required = false) Payment.Method method,
                            @RequestParam(required = false) UUID propertyId,
                            @RequestParam(required = false) UUID tenantId,
                            @RequestParam(defaultValue = "false") boolean includeVoided) {
        return service.list(from, to, method, propertyId, tenantId, includeVoided);
    }

    @GetMapping("/{id}")
    public PaymentView get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** Records a payment and returns its receipt. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Receipt record(@Valid @RequestBody PaymentRequest request) {
        return service.record(request);
    }

    @GetMapping("/{id}/receipt")
    public Receipt receipt(@PathVariable UUID id) {
        return service.receipt(id);
    }

    @GetMapping("/{id}/receipt.pdf")
    public ResponseEntity<byte[]> receiptPdf(@PathVariable UUID id) {
        return receiptPdf.response(service.receipt(id));
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasRole('ADMIN')")
    public PaymentView voidPayment(@PathVariable UUID id, @Valid @RequestBody VoidRequest request) {
        return service.voidPayment(id, request.reason());
    }
}
