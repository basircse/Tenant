package com.revesoft.tms.maintenance;

import com.revesoft.tms.agreement.Agreement;
import com.revesoft.tms.agreement.AgreementRepository;
import com.revesoft.tms.audit.AuditService;
import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.common.CodeGenerator;
import com.revesoft.tms.common.NameLookup;
import com.revesoft.tms.common.NameLookup.Names;
import com.revesoft.tms.notification.NotificationService;
import com.revesoft.tms.security.AccessGuard;
import com.revesoft.tms.security.AuthUser;
import com.revesoft.tms.storage.FileDownload;
import com.revesoft.tms.storage.FileStorage;
import com.revesoft.tms.storage.StoredFile;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Maintenance requests (SRS §13). */
@Service
public class MaintenanceService {

    private final MaintenanceRepository requests;
    private final MaintenanceHistoryRepository history;
    private final AgreementRepository agreements;
    private final NameLookup names;
    private final CodeGenerator codes;
    private final AccessGuard guard;
    private final AuditService audit;
    private final NotificationService notifications;
    private final FileStorage storage;

    public MaintenanceService(MaintenanceRepository requests, MaintenanceHistoryRepository history,
                              AgreementRepository agreements, NameLookup names, CodeGenerator codes,
                              AccessGuard guard, AuditService audit, NotificationService notifications,
                              FileStorage storage) {
        this.requests = requests;
        this.history = history;
        this.agreements = agreements;
        this.names = names;
        this.codes = codes;
        this.guard = guard;
        this.audit = audit;
        this.notifications = notifications;
        this.storage = storage;
    }

    // ---------------------------------------------------------------- DTOs

    /** {@code tenantId} is required for staff; tenants always raise requests for their own unit. */
    public record CreateRequest(UUID tenantId, @NotBlank @Size(max = 200) String title,
                                @NotNull MaintenanceRequest.Type type, @NotNull MaintenanceRequest.Priority priority,
                                @NotBlank String description, String attachment) {
    }

    public record AssignRequest(@NotBlank @Size(max = 150) String assignedTo) {
    }

    public record StatusRequest(@NotNull MaintenanceRequest.Status status, String note) {
    }

    public record HistoryView(MaintenanceRequest.Status status, String changedBy, String note, Instant at) {
    }

    public record RequestView(UUID id, String code, UUID tenantId, String tenantName, UUID unitId, String unitNo,
                              UUID propertyId, String propertyName, String title, MaintenanceRequest.Type type,
                              String description, MaintenanceRequest.Priority priority, String attachment,
                              boolean hasAttachment, String attachmentType, String assignedTo, MaintenanceRequest.Status status, Instant createdAt,
                              List<HistoryView> history) {
    }

    // ---------------------------------------------------------------- Queries

    @Transactional(readOnly = true)
    public List<RequestView> list(MaintenanceRequest.Status status, MaintenanceRequest.Priority priority,
                                  UUID propertyId, boolean pendingOnly, String q) {
        guard.requireStaff();
        Set<UUID> scope = guard.propertyScope();
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return views(requests.findAllByOrderByCreatedAtDesc().stream()
                .filter(r -> AccessGuard.inScope(scope, r.getPropertyId()))
                .filter(r -> !pendingOnly || r.getStatus().isPending())
                .filter(r -> status == null || r.getStatus() == status)
                .filter(r -> priority == null || r.getPriority() == priority)
                .filter(r -> propertyId == null || r.getPropertyId().equals(propertyId))
                .toList(), false).stream()
                .filter(v -> query.isEmpty() || contains(query, v.code(), v.title(), v.tenantName(), v.unitNo()))
                .sorted(Comparator.comparing(RequestView::priority).reversed()
                        .thenComparing(RequestView::createdAt, Comparator.reverseOrder()))
                .toList();
    }

    @Transactional(readOnly = true)
    public RequestView get(UUID id) {
        return views(List.of(load(id)), true).get(0);
    }

    /** A tenant's own requests (tenant portal). */
    @Transactional(readOnly = true)
    public List<RequestView> forTenant(UUID tenantId) {
        return views(requests.findByTenantIdOrderByCreatedAtDesc(tenantId), false);
    }

    @Transactional(readOnly = true)
    public RequestView getForTenant(UUID tenantId, UUID id) {
        return views(List.of(ownRequest(tenantId, id)), true).get(0);
    }

    // ---------------------------------------------------------------- Commands

    @Transactional
    public RequestView create(CreateRequest r) {
        AuthUser me = guard.user();
        UUID tenantId;
        if (me.isTenant()) {
            tenantId = me.tenantId();
        } else {
            guard.requireStaff();
            if (r.tenantId() == null) {
                throw new BusinessException("Select the tenant");
            }
            tenantId = r.tenantId();
        }
        Agreement a = agreements.findByTenantIdAndStatusIn(tenantId, Agreement.CURRENT)
                .orElseThrow(() -> new BusinessException("Maintenance requests need an active rental agreement"));
        if (me.isStaff()) {
            guard.checkProperty(a.getPropertyId());
        }
        MaintenanceRequest m = new MaintenanceRequest();
        m.setCode(codes.next("MR"));
        m.setTenantId(tenantId);
        m.setUnitId(a.getUnitId());
        m.setPropertyId(a.getPropertyId());
        m.setTitle(r.title().trim());
        m.setType(r.type());
        m.setPriority(r.priority());
        m.setDescription(r.description().trim());
        m.setAttachment(r.attachment());
        requests.save(m);
        addHistory(m, MaintenanceRequest.Status.OPEN, me.name(), null);
        audit.log("Created Maintenance Request", "Maintenance", m.getCode(), m.getTitle());
        notifications.notifyStaff(m.getPropertyId(), "New maintenance request",
                m.getCode() + ": " + m.getTitle() + " (" + m.getPriority().name().toLowerCase() + ")", null);
        return views(List.of(m), true).get(0);
    }

    @Transactional
    public RequestView assign(UUID id, String assignedTo) {
        MaintenanceRequest m = load(id);
        if (m.getStatus() == MaintenanceRequest.Status.CLOSED) {
            throw new BusinessException("Request is closed");
        }
        m.setAssignedTo(assignedTo.trim());
        if (m.getStatus() == MaintenanceRequest.Status.OPEN) {
            changeStatus(m, MaintenanceRequest.Status.ASSIGNED, "Assigned to " + m.getAssignedTo());
        }
        audit.log("Updated Maintenance Request", "Maintenance", m.getCode(), "Assigned: " + m.getAssignedTo());
        return get(id);
    }

    /** Status only moves forward: OPEN → ASSIGNED → IN_PROGRESS → COMPLETED → CLOSED. */
    @Transactional
    public RequestView updateStatus(UUID id, StatusRequest r) {
        MaintenanceRequest m = load(id);
        if (r.status().ordinal() <= m.getStatus().ordinal()) {
            throw new BusinessException("Status can only move forward");
        }
        if (r.status().ordinal() >= MaintenanceRequest.Status.ASSIGNED.ordinal()
                && (m.getAssignedTo() == null || m.getAssignedTo().isBlank())) {
            throw new BusinessException("Assign the request to someone first");
        }
        changeStatus(m, r.status(), r.note());
        audit.log("Updated Maintenance Request", "Maintenance", m.getCode(), "Status: " + r.status());
        return get(id);
    }

    // ---------------------------------------------------------------- Attachment (one file per request)

    @Transactional
    public RequestView attach(UUID id, MultipartFile file) {
        MaintenanceRequest m = load(id);
        storeAttachment(m, file);
        return get(id);
    }

    @Transactional
    public RequestView attachForTenant(UUID tenantId, UUID id, MultipartFile file) {
        MaintenanceRequest m = ownRequest(tenantId, id);
        storeAttachment(m, file);
        return views(List.of(m), true).get(0);
    }

    @Transactional(readOnly = true)
    public FileDownload attachment(UUID id) {
        return attachmentOf(load(id));
    }

    @Transactional(readOnly = true)
    public FileDownload attachmentForTenant(UUID tenantId, UUID id) {
        return attachmentOf(ownRequest(tenantId, id));
    }

    private void storeAttachment(MaintenanceRequest m, MultipartFile file) {
        if (m.getStatus() == MaintenanceRequest.Status.CLOSED) {
            throw new BusinessException("Request is closed");
        }
        StoredFile stored = storage.save(file);
        storage.deleteAfterCommit(m.getAttachmentKey());
        m.setAttachmentKey(stored.key());
        m.setAttachmentType(stored.contentType());
        m.setAttachmentSize(stored.sizeBytes());
        m.setAttachmentName(stored.originalName());
        audit.log("Updated Maintenance Request", "Maintenance", m.getCode(), "Attachment uploaded");
    }

    private FileDownload attachmentOf(MaintenanceRequest m) {
        if (m.getAttachmentKey() == null) {
            throw BusinessException.notFound("Attachment");
        }
        return storage.download(m.getAttachmentKey(), m.getAttachmentType(), m.getAttachmentSize(),
                m.getAttachmentName(), m.getCode());
    }

    private MaintenanceRequest ownRequest(UUID tenantId, UUID id) {
        MaintenanceRequest r = AccessGuard.owned(requests.findById(id), "Request");
        if (!r.getTenantId().equals(tenantId)) {
            throw BusinessException.notFound("Request");
        }
        return r;
    }

    // ---------------------------------------------------------------- Helpers

    private MaintenanceRequest load(UUID id) {
        guard.requireStaff();
        MaintenanceRequest m = AccessGuard.owned(requests.findById(id), "Request");
        guard.checkProperty(m.getPropertyId());
        return m;
    }

    private void changeStatus(MaintenanceRequest m, MaintenanceRequest.Status status, String note) {
        m.setStatus(status);
        addHistory(m, status, guard.user().name(), note);
        notifications.notifyTenant(m.getTenantId(), "Maintenance update",
                m.getCode() + " \"" + m.getTitle() + "\" is now " + status.name().toLowerCase().replace('_', ' ') + ".",
                null);
    }

    private void addHistory(MaintenanceRequest m, MaintenanceRequest.Status status, String by, String note) {
        MaintenanceHistory h = new MaintenanceHistory();
        h.setRequestId(m.getId());
        h.setStatus(status);
        h.setChangedBy(by);
        h.setNote(note == null || note.isBlank() ? null : note.trim());
        history.save(h);
    }

    private List<RequestView> views(List<MaintenanceRequest> list, boolean withHistory) {
        Names n = names.load(list.stream().map(MaintenanceRequest::getTenantId).collect(Collectors.toSet()),
                list.stream().map(MaintenanceRequest::getUnitId).collect(Collectors.toSet()),
                list.stream().map(MaintenanceRequest::getPropertyId).collect(Collectors.toSet()));
        return list.stream().map(m -> new RequestView(m.getId(), m.getCode(), m.getTenantId(), n.tenant(m.getTenantId()),
                m.getUnitId(), n.unit(m.getUnitId()), m.getPropertyId(), n.property(m.getPropertyId()), m.getTitle(),
                m.getType(), m.getDescription(), m.getPriority(), m.getAttachment(), m.getAttachmentKey() != null,
                m.getAttachmentType(), m.getAssignedTo(), m.getStatus(),
                m.getCreatedAt(),
                withHistory ? history.findByRequestIdOrderByCreatedAtAsc(m.getId()).stream()
                        .map(h -> new HistoryView(h.getStatus(), h.getChangedBy(), h.getNote(), h.getCreatedAt()))
                        .toList() : null)).toList();
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
