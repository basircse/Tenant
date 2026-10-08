package com.revesoft.tms.notification;

import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.security.AccessGuard;
import com.revesoft.tms.security.CurrentUser;
import com.revesoft.tms.security.Role;
import com.revesoft.tms.security.TenantContext;
import com.revesoft.tms.user.AppUser;
import com.revesoft.tms.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications (SRS §14). Every saved notification also publishes {@link NotificationCreated},
 * which the push module delivers to the user's devices after the transaction commits.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repo;
    private final UserRepository users;
    private final ApplicationEventPublisher events;

    public NotificationService(NotificationRepository repo, UserRepository users, ApplicationEventPublisher events) {
        this.repo = repo;
        this.users = users;
        this.events = events;
    }

    public record NotificationView(UUID id, String title, String body, boolean read, Instant createdAt) {
        static NotificationView of(AppNotification n) {
            return new NotificationView(n.getId(), n.getTitle(), n.getBody(), n.isRead(), n.getCreatedAt());
        }
    }

    public record Inbox(long unread, List<NotificationView> items) {
    }

    @Transactional
    public void notifyUser(UUID userId, String title, String body, String dedupKey) {
        if (dedupKey != null && repo.existsByUserIdAndDedupKey(userId, dedupKey)) {
            log.debug("Notification to {} skipped, already sent: {}", userId, dedupKey);
            return;
        }
        AppNotification n = new AppNotification();
        n.setUserId(userId);
        n.setTitle(title);
        n.setBody(body);
        n.setDedupKey(dedupKey);
        repo.save(n);
        log.debug("Notification to {}: {}", userId, title);
        UUID org = TenantContext.current();
        if (!TenantContext.ROOT.equals(org) && !TenantContext.NONE.equals(org)) {
            events.publishEvent(new NotificationCreated(org, n.getId(), userId, title, body));
        }
    }

    /** Admins, plus managers assigned to {@code propertyId} (or all managers when null). */
    @Transactional
    public void notifyStaff(UUID propertyId, String title, String body, String dedupKey) {
        for (AppUser u : users.findByRoleInAndStatus(List.of(Role.ADMIN, Role.MANAGER), AppUser.Status.ACTIVE)) {
            boolean relevant = u.getRole() == Role.ADMIN
                    || propertyId == null || u.getPropertyIds().contains(propertyId);
            if (relevant) {
                notifyUser(u.getId(), title, body, dedupKey);
            }
        }
    }

    /** Organisation administrators only (licence matters). */
    @Transactional
    public void notifyAdmins(String title, String body, String dedupKey) {
        for (AppUser u : users.findByRoleInAndStatus(List.of(Role.ADMIN), AppUser.Status.ACTIVE)) {
            notifyUser(u.getId(), title, body, dedupKey);
        }
    }

    @Transactional
    public void notifyTenant(UUID tenantId, String title, String body, String dedupKey) {
        users.findByTenantId(tenantId).ifPresent(u -> notifyUser(u.getId(), title, body, dedupKey));
    }

    @Transactional(readOnly = true)
    public Inbox inbox(int limit) {
        UUID me = CurrentUser.get().userId();
        List<NotificationView> items = repo.findByUserIdOrderByCreatedAtDesc(me, PageRequest.of(0, Math.min(limit, 500)))
                .stream().map(NotificationView::of).toList();
        return new Inbox(repo.countByUserIdAndReadFalse(me), items);
    }

    @Transactional
    public void markRead(UUID id) {
        AppNotification n = AccessGuard.owned(repo.findById(id), "Notification");
        if (!n.getUserId().equals(CurrentUser.get().userId())) {
            throw BusinessException.notFound("Notification");
        }
        n.setRead(true);
    }

    @Transactional
    public void markAllRead() {
        repo.markAllRead(CurrentUser.get().userId());
    }
}
