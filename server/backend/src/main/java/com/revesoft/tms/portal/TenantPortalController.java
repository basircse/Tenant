package com.revesoft.tms.portal;

import com.revesoft.tms.agreement.AgreementService;
import com.revesoft.tms.agreement.AgreementService.AgreementView;
import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.maintenance.MaintenanceService;
import com.revesoft.tms.maintenance.MaintenanceService.CreateRequest;
import com.revesoft.tms.maintenance.MaintenanceService.RequestView;
import com.revesoft.tms.payment.PaymentService;
import com.revesoft.tms.payment.PaymentService.PaymentList;
import com.revesoft.tms.payment.PaymentService.Receipt;
import com.revesoft.tms.rent.RentService;
import com.revesoft.tms.report.ReceiptPdf;
import com.revesoft.tms.rent.RentService.InvoiceList;
import com.revesoft.tms.rent.RentService.InvoiceView;
import com.revesoft.tms.security.CurrentUser;
import com.revesoft.tms.tenant.Tenant;
import com.revesoft.tms.tenant.TenantRepository;
import com.revesoft.tms.tenant.TenantService;
import com.revesoft.tms.tenant.TenantService.DocumentView;
import com.revesoft.tms.tenant.TenantService.TenantView;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Tenant self-service API used by the tenant app: own profile, flat, dues, payments, receipts and
 * maintenance requests. Every endpoint is limited to the caller's own tenant record.
 */
@RestController
@RequestMapping("/api/me")
@PreAuthorize("hasRole('TENANT')")
public class TenantPortalController {

    private final TenantRepository tenants;
    private final TenantService tenantService;
    private final AgreementService agreements;
    private final RentService rent;
    private final PaymentService payments;
    private final MaintenanceService maintenance;
    private final ReceiptPdf receiptPdf;

    public TenantPortalController(TenantRepository tenants, TenantService tenantService, AgreementService agreements,
                                  RentService rent, PaymentService payments, MaintenanceService maintenance,
                                  ReceiptPdf receiptPdf) {
        this.tenants = tenants;
        this.tenantService = tenantService;
        this.agreements = agreements;
        this.rent = rent;
        this.payments = payments;
        this.maintenance = maintenance;
        this.receiptPdf = receiptPdf;
    }

    public record Home(TenantView tenant, AgreementView currentAgreement, BigDecimal outstanding,
                       InvoiceView nextDue, List<InvoiceView> unpaid, int openMaintenance) {
    }

    @GetMapping("/home")
    @Transactional(readOnly = true)
    public Home home() {
        UUID tenantId = myTenantId();
        TenantView me = tenantService.view(loadTenant(tenantId));
        AgreementView current = agreements.forTenant(tenantId).stream()
                .filter(a -> a.status().isCurrent()).findFirst().orElse(null);
        InvoiceList invoices = rent.forTenant(tenantId);
        List<InvoiceView> unpaid = invoices.items().stream()
                .filter(i -> i.status().isOpen())
                .sorted(Comparator.comparing(InvoiceView::dueDate))
                .toList();
        int openMaintenance = (int) maintenance.forTenant(tenantId).stream().filter(r -> r.status().isPending()).count();
        return new Home(me, current, invoices.outstanding(), unpaid.isEmpty() ? null : unpaid.get(0), unpaid,
                openMaintenance);
    }

    @GetMapping("/profile")
    @Transactional(readOnly = true)
    public TenantView profile() {
        return tenantService.view(loadTenant(myTenantId()));
    }

    @GetMapping("/agreements")
    public List<AgreementView> agreements() {
        return agreements.forTenant(myTenantId());
    }

    @GetMapping("/invoices")
    public InvoiceList invoices() {
        return rent.forTenant(myTenantId());
    }

    @GetMapping("/payments")
    public PaymentList payments() {
        return payments.forTenant(myTenantId());
    }

    @GetMapping("/payments/{id}/receipt")
    public Receipt receipt(@PathVariable UUID id) {
        return payments.receiptForTenant(myTenantId(), id);
    }

    @GetMapping("/payments/{id}/receipt.pdf")
    public ResponseEntity<byte[]> receiptPdf(@PathVariable UUID id) {
        return receiptPdf.response(payments.receiptForTenant(myTenantId(), id));
    }

    @GetMapping("/documents")
    public List<DocumentView> documents() {
        return tenantService.documentsForTenant(myTenantId());
    }

    @GetMapping("/documents/{id}/file")
    public ResponseEntity<Resource> documentFile(@PathVariable UUID id) {
        return tenantService.documentFileForTenant(myTenantId(), id).toResponse();
    }

    @GetMapping("/maintenance")
    public List<RequestView> maintenance() {
        return maintenance.forTenant(myTenantId());
    }

    @GetMapping("/maintenance/{id}")
    public RequestView maintenanceRequest(@PathVariable UUID id) {
        return maintenance.getForTenant(myTenantId(), id);
    }

    @PostMapping("/maintenance")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestView createMaintenance(@Valid @RequestBody CreateRequest request) {
        myTenantId();
        return maintenance.create(request);
    }

    /** Multipart upload with field {@code file} (photo or PDF); replaces any earlier attachment. */
    @PostMapping(path = "/maintenance/{id}/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RequestView attachToMaintenance(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        return maintenance.attachForTenant(myTenantId(), id, file);
    }

    @GetMapping("/maintenance/{id}/attachment")
    public ResponseEntity<Resource> maintenanceAttachment(@PathVariable UUID id) {
        return maintenance.attachmentForTenant(myTenantId(), id).toResponse();
    }

    private static UUID myTenantId() {
        UUID id = CurrentUser.get().tenantId();
        if (id == null) {
            throw BusinessException.forbidden("Your login is not linked to a tenant record");
        }
        return id;
    }

    private Tenant loadTenant(UUID id) {
        return tenants.findById(id).orElseThrow(() -> BusinessException.notFound("Tenant"));
    }
}
