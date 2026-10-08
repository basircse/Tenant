package com.revesoft.tms.tenant;

import com.revesoft.tms.agreement.Agreement;
import com.revesoft.tms.agreement.AgreementRepository;
import com.revesoft.tms.audit.AuditService;
import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.common.CodeGenerator;
import com.revesoft.tms.common.Money;
import com.revesoft.tms.notification.NotificationService;
import com.revesoft.tms.property.Property;
import com.revesoft.tms.property.PropertyRepository;
import com.revesoft.tms.property.Unit;
import com.revesoft.tms.property.UnitRepository;
import com.revesoft.tms.rent.RentInvoice;
import com.revesoft.tms.rent.RentInvoiceRepository;
import com.revesoft.tms.security.AccessGuard;
import com.revesoft.tms.storage.FileDownload;
import com.revesoft.tms.storage.FileStorage;
import com.revesoft.tms.storage.StoredFile;
import com.revesoft.tms.user.AppUser;
import com.revesoft.tms.user.UserRepository;
import com.revesoft.tms.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Tenant management (SRS §8). */
@Service
public class TenantService {

    private final TenantRepository tenants;
    private final TenantDocumentRepository documents;
    private final AgreementRepository agreements;
    private final RentInvoiceRepository invoices;
    private final UnitRepository units;
    private final PropertyRepository properties;
    private final UserRepository users;
    private final UserService userService;
    private final CodeGenerator codes;
    private final AccessGuard guard;
    private final AuditService audit;
    private final NotificationService notifications;
    private final FileStorage storage;

    public TenantService(TenantRepository tenants, TenantDocumentRepository documents, AgreementRepository agreements,
                         RentInvoiceRepository invoices, UnitRepository units, PropertyRepository properties,
                         UserRepository users, UserService userService, CodeGenerator codes, AccessGuard guard,
                         AuditService audit, NotificationService notifications, FileStorage storage) {
        this.tenants = tenants;
        this.documents = documents;
        this.agreements = agreements;
        this.invoices = invoices;
        this.units = units;
        this.properties = properties;
        this.users = users;
        this.userService = userService;
        this.codes = codes;
        this.guard = guard;
        this.audit = audit;
        this.notifications = notifications;
        this.storage = storage;
    }

    // ---------------------------------------------------------------- DTOs

    public record LoginRequest(@NotBlank @Size(min = 3, max = 60) String username, @NotBlank String password) {
    }

    public record TenantRequest(
            @NotBlank @Size(max = 150) String name,
            @NotBlank @Pattern(regexp = "\\+?\\d{10,14}", message = "invalid mobile number") String mobile,
            @Email String email,
            @NotBlank @Size(max = 50) String nid,
            @Past LocalDate dateOfBirth,
            String presentAddress,
            String emergencyContact,
            String occupation,
            /** Only applied while the tenant has no active agreement. */
            Tenant.Status status,
            /** Optional portal login, created together with the tenant. */
            @Valid LoginRequest login) {
    }

    public record TenantView(UUID id, String code, String name, String mobile, String email, String nid,
                             LocalDate dateOfBirth, String presentAddress, String emergencyContact,
                             String occupation, Tenant.Status status, Instant createdAt, CurrentUnit currentUnit,
                             BigDecimal outstanding) {
    }

    public record CurrentUnit(UUID agreementId, UUID unitId, String unitNo, UUID propertyId, String propertyName,
                              BigDecimal monthlyTotal, LocalDate endDate) {
    }

    public record DocumentRequest(@NotBlank @Size(max = 150) String name, @NotBlank @Size(max = 30) String docType,
                                  @NotBlank String reference) {
    }

    /** {@code hasFile}: an uploaded file can be downloaded from {@code .../documents/{id}/file}. */
    public record DocumentView(UUID id, String name, String docType, String reference, Instant addedAt,
                               boolean hasFile, String contentType, Long sizeBytes) {
        static DocumentView of(TenantDocument d) {
            return new DocumentView(d.getId(), d.getName(), d.getDocType(), d.getReference(), d.getCreatedAt(),
                    d.getFileKey() != null, d.getContentType(), d.getSizeBytes());
        }
    }

    public record TenantDetail(TenantView tenant, String portalUsername, AppUser.Status portalStatus,
                               List<DocumentView> documents) {
    }

    // ---------------------------------------------------------------- Queries

    @Transactional(readOnly = true)
    public List<TenantView> list(String q, Tenant.Status status, UUID propertyId) {
        guard.requireStaff();
        Set<UUID> scope = guard.propertyScope();
        Map<UUID, List<Agreement>> byTenant = agreements.findAll().stream()
                .collect(Collectors.groupingBy(Agreement::getTenantId));
        Map<UUID, BigDecimal> due = outstandingByTenant();
        Lookup lookup = lookup();
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return tenants.findAllByOrderByNameAsc().stream()
                .filter(t -> visible(scope, byTenant.getOrDefault(t.getId(), List.of())))
                .filter(t -> status == null || t.getStatus() == status)
                .map(t -> view(t, current(byTenant.get(t.getId())), due.getOrDefault(t.getId(), BigDecimal.ZERO), lookup))
                .filter(v -> propertyId == null || (v.currentUnit() != null && propertyId.equals(v.currentUnit().propertyId())))
                .filter(v -> query.isEmpty() || contains(query, v.name(), v.code(), v.mobile(), v.email(), v.nid(),
                        v.currentUnit() == null ? null : v.currentUnit().unitNo()))
                .toList();
    }

    @Transactional(readOnly = true)
    public TenantDetail get(UUID id) {
        Tenant t = load(id);
        AppUser login = users.findByTenantId(id).orElse(null);
        return new TenantDetail(view(t), login == null ? null : login.getUsername(),
                login == null ? null : login.getStatus(),
                documents.findByTenantIdOrderByCreatedAtAsc(id).stream().map(DocumentView::of).toList());
    }

    /** Full view of one tenant (also used by the tenant portal). */
    @Transactional(readOnly = true)
    public TenantView view(Tenant t) {
        Agreement a = agreements.findByTenantIdAndStatusIn(t.getId(), Agreement.CURRENT).orElse(null);
        BigDecimal due = invoices.findByTenantIdOrderByBillingMonthDesc(t.getId()).stream()
                .filter(i -> i.getStatus().isOpen()).map(RentInvoice::dueAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return view(t, a, due, lookup());
    }

    // ---------------------------------------------------------------- Commands

    @Transactional
    public TenantDetail create(TenantRequest r) {
        guard.requireStaff();
        Tenant t = new Tenant();
        t.setCode(codes.next("T", 10001, 0));
        apply(t, r);
        t.setStatus(r.status() == null || r.status() == Tenant.Status.ACTIVE ? Tenant.Status.INACTIVE : r.status());
        tenants.save(t);
        if (r.login() != null) {
            userService.createTenantLogin(t.getId(), t.getName(), t.getEmail(), t.getMobile(),
                    r.login().username(), r.login().password());
        }
        audit.log("Created Tenant", "Tenant", t.getCode(), t.getName());
        notifications.notifyStaff(null, "New tenant registered", t.getName() + " (" + t.getCode() + ") was registered.", null);
        return get(t.getId());
    }

    @Transactional
    public TenantDetail update(UUID id, TenantRequest r) {
        Tenant t = load(id);
        apply(t, r);
        boolean hasCurrent = agreements.findByTenantIdAndStatusIn(id, Agreement.CURRENT).isPresent();
        if (!hasCurrent && r.status() != null) {
            if (r.status() == Tenant.Status.ACTIVE) {
                throw new BusinessException("A tenant becomes Active automatically when an agreement is activated");
            }
            t.setStatus(r.status());
        }
        if (r.login() != null) {
            if (users.findByTenantId(id).isPresent()) {
                throw BusinessException.conflict("This tenant already has a portal login");
            }
            userService.createTenantLogin(t.getId(), t.getName(), t.getEmail(), t.getMobile(),
                    r.login().username(), r.login().password());
        }
        audit.log("Updated Tenant", "Tenant", t.getCode(), t.getName());
        return get(id);
    }

    @Transactional
    public DocumentView addDocument(UUID tenantId, DocumentRequest r) {
        Tenant t = load(tenantId);
        TenantDocument d = new TenantDocument();
        d.setTenantId(t.getId());
        d.setName(r.name().trim());
        d.setDocType(r.docType().trim());
        d.setReference(r.reference().trim());
        documents.save(d);
        audit.log("Uploaded Tenant Document", "Tenant", t.getCode(), d.getName());
        return DocumentView.of(d);
    }

    /** Adds a document with an uploaded file (PDF / JPEG / PNG / WebP). */
    @Transactional
    public DocumentView uploadDocument(UUID tenantId, String name, String docType, String reference,
                                       MultipartFile file) {
        Tenant t = load(tenantId);
        String n = name == null ? "" : name.trim();
        String type = docType == null ? "" : docType.trim();
        if (n.isEmpty() || n.length() > 150) {
            throw new BusinessException("Document name is required (max 150 characters)");
        }
        if (type.isEmpty() || type.length() > 30) {
            throw new BusinessException("Document type is required (max 30 characters)");
        }
        StoredFile stored = storage.save(file);
        TenantDocument d = new TenantDocument();
        d.setTenantId(t.getId());
        d.setName(n);
        d.setDocType(type);
        String ref = reference == null || reference.isBlank() ? stored.originalName() : reference.trim();
        d.setReference(ref == null ? n : ref);
        d.setFileKey(stored.key());
        d.setContentType(stored.contentType());
        d.setSizeBytes(stored.sizeBytes());
        d.setOriginalName(stored.originalName());
        documents.save(d);
        audit.log("Uploaded Tenant Document", "Tenant", t.getCode(), d.getName() + " (file)");
        return DocumentView.of(d);
    }

    @Transactional(readOnly = true)
    public FileDownload documentFile(UUID tenantId, UUID documentId) {
        Tenant t = load(tenantId);
        return fileOf(document(t.getId(), documentId));
    }

    @Transactional
    public void removeDocument(UUID tenantId, UUID documentId) {
        Tenant t = load(tenantId);
        TenantDocument d = document(t.getId(), documentId);
        documents.delete(d);
        storage.deleteAfterCommit(d.getFileKey());
        audit.log("Removed Tenant Document", "Tenant", t.getCode(), d.getName());
    }

    /** A tenant's own documents (tenant portal). */
    @Transactional(readOnly = true)
    public List<DocumentView> documentsForTenant(UUID tenantId) {
        return documents.findByTenantIdOrderByCreatedAtAsc(tenantId).stream().map(DocumentView::of).toList();
    }

    /** A tenant's own document file (tenant portal). */
    @Transactional(readOnly = true)
    public FileDownload documentFileForTenant(UUID tenantId, UUID documentId) {
        return fileOf(document(tenantId, documentId));
    }

    private TenantDocument document(UUID tenantId, UUID documentId) {
        TenantDocument d = AccessGuard.owned(documents.findById(documentId), "Document");
        if (!d.getTenantId().equals(tenantId)) {
            throw BusinessException.notFound("Document");
        }
        return d;
    }

    private FileDownload fileOf(TenantDocument d) {
        if (d.getFileKey() == null) {
            throw BusinessException.notFound("File");
        }
        return storage.download(d.getFileKey(), d.getContentType(), d.getSizeBytes(), d.getOriginalName(), d.getName());
    }

    // ---------------------------------------------------------------- Helpers

    /** Loads a tenant the caller (staff) may access. */
    public Tenant load(UUID id) {
        guard.requireStaff();
        Tenant t = AccessGuard.owned(tenants.findById(id), "Tenant");
        if (!visible(guard.propertyScope(), agreements.findByTenantId(id))) {
            throw BusinessException.forbidden("You are not assigned to this tenant's property");
        }
        return t;
    }

    /** Managers see tenants linked to their properties and tenants not yet linked to any unit. */
    private static boolean visible(Set<UUID> scope, List<Agreement> tenantAgreements) {
        return scope == null || tenantAgreements.isEmpty()
                || tenantAgreements.stream().anyMatch(a -> scope.contains(a.getPropertyId()));
    }

    private void apply(Tenant t, TenantRequest r) {
        String mobile = r.mobile().trim();
        String nid = r.nid().trim();
        if (tenants.existsByMobileAndIdNot(mobile, t.getId())) {
            throw BusinessException.conflict("Another tenant already uses this mobile number");
        }
        if (tenants.existsByNidIgnoreCaseAndIdNot(nid, t.getId())) {
            throw BusinessException.conflict("Another tenant already uses this NID/Passport number");
        }
        t.setName(r.name().trim());
        t.setMobile(mobile);
        t.setEmail(r.email() == null || r.email().isBlank() ? null : r.email().trim());
        t.setNid(nid);
        t.setDateOfBirth(r.dateOfBirth());
        t.setPresentAddress(r.presentAddress());
        t.setEmergencyContact(r.emergencyContact());
        t.setOccupation(r.occupation());
    }

    private record Lookup(Map<UUID, Unit> units, Map<UUID, Property> properties) {
    }

    private Lookup lookup() {
        return new Lookup(
                units.findAll().stream().collect(Collectors.toMap(Unit::getId, Function.identity())),
                properties.findAll().stream().collect(Collectors.toMap(Property::getId, Function.identity())));
    }

    private Map<UUID, BigDecimal> outstandingByTenant() {
        return invoices.findByStatusIn(RentInvoice.Status.OPEN).stream()
                .collect(Collectors.groupingBy(RentInvoice::getTenantId,
                        Collectors.reducing(BigDecimal.ZERO, RentInvoice::dueAmount, BigDecimal::add)));
    }

    private static Agreement current(List<Agreement> list) {
        return list == null ? null : list.stream().filter(a -> a.getStatus().isCurrent()).findFirst().orElse(null);
    }

    private static TenantView view(Tenant t, Agreement a, BigDecimal due, Lookup lookup) {
        CurrentUnit cu = null;
        if (a != null) {
            Unit u = lookup.units().get(a.getUnitId());
            Property p = lookup.properties().get(a.getPropertyId());
            cu = new CurrentUnit(a.getId(), a.getUnitId(), u == null ? null : u.getUnitNo(), a.getPropertyId(),
                    p == null ? null : p.getName(), a.totalMonthly(), a.getEndDate());
        }
        return new TenantView(t.getId(), t.getCode(), t.getName(), t.getMobile(), t.getEmail(), t.getNid(),
                t.getDateOfBirth(), t.getPresentAddress(), t.getEmergencyContact(), t.getOccupation(),
                t.getStatus(), t.getCreatedAt(), cu, Money.of(due));
    }

    private static boolean contains(String query, String... values) {
        for (String v : values) {
            if (v != null && v.toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }
}
