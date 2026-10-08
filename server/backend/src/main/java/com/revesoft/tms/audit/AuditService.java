package com.revesoft.tms.audit;

import com.revesoft.tms.security.AuthUser;
import com.revesoft.tms.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records important activity (SRS §17). Always written in the caller's transaction. */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    public record AuditView(UUID id, Instant at, String userName, String action, String entityType,
                            String entityId, String details) {
        static AuditView of(AuditLog a) {
            return new AuditView(a.getId(), a.getCreatedAt(), a.getUserName(), a.getAction(), a.getEntityType(),
                    a.getEntityId(), a.getDetails());
        }
    }

    @Transactional
    public void log(String action, String entityType, String entityId, String details) {
        AuthUser user = CurrentUser.orNull();
        logAs(user == null ? null : user.userId(), user == null ? "System" : user.name(),
                action, entityType, entityId, details);
    }

    @Transactional
    public void logAs(UUID userId, String userName, String action, String entityType, String entityId,
                      String details) {
        log.info("Audit: {} {} {} by {}{}", action, entityType, entityId, userName,
                details == null || details.isBlank() ? "" : " - " + details);
        AuditLog entry = new AuditLog();
        entry.setUserId(userId);
        entry.setUserName(userName);
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setDetails(details);
        repo.save(entry);
    }

    @Transactional(readOnly = true)
    public List<AuditView> search(String entityType, String q, int limit) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        String type = entityType == null || entityType.isBlank() ? null : entityType;
        return repo.search(type, like, PageRequest.of(0, Math.min(Math.max(limit, 1), 1000)))
                .stream().map(AuditView::of).toList();
    }
}
