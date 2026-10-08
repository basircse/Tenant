package com.revesoft.tms.push;

import com.revesoft.tms.license.LicenseService;
import com.revesoft.tms.license.LicenseState;
import com.revesoft.tms.notification.NotificationCreated;
import com.revesoft.tms.org.Organization;
import com.revesoft.tms.org.OrganizationRepository;
import com.revesoft.tms.push.PushSender.PushMessage;
import com.revesoft.tms.security.TxRunner;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes every new in-app notification to the recipient's registered devices. Runs only after the
 * notification's transaction has committed, on a small background pool, so a slow or failing push
 * never affects the business request. Organisations whose licence is pending, rejected, suspended
 * or expired get no pushes. Tokens that FCM reports as dead are deleted.
 */
@Component
public class PushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

    private final PushTokenRepository tokens;
    private final OrganizationRepository orgs;
    private final LicenseService licenses;
    private final PushSender sender;
    private final TxRunner tx;
    private final ExecutorService executor;

    public PushDispatcher(PushTokenRepository tokens, OrganizationRepository orgs, LicenseService licenses,
                          PushSender sender, TxRunner tx) {
        this.tokens = tokens;
        this.orgs = orgs;
        this.licenses = licenses;
        this.sender = sender;
        this.tx = tx;
        AtomicInteger n = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(1, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(10_000),
                r -> {
                    Thread t = new Thread(r, "push-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                (r, pool) -> log.warn("Push queue full; a push notification was dropped"));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotification(NotificationCreated event) {
        executor.execute(() -> deliver(event));
    }

    void deliver(NotificationCreated e) {
        try {
            List<String> targets = tx.inOrg(e.orgId(), () ->
                    tokens.findByUserId(e.userId()).stream().map(PushToken::getToken).toList());
            if (targets.isEmpty()) {
                return;
            }
            Organization org = tx.asRoot(() -> orgs.findById(e.orgId()).orElse(null));
            if (org == null || !mayPush(licenses.stateOf(org))) {
                return;
            }
            Set<String> dead = sender.send(targets, new PushMessage(e.title(), e.body(),
                    Map.of("type", "notification", "notificationId", e.notificationId().toString())));
            if (dead != null && !dead.isEmpty()) {
                tx.inOrg(e.orgId(), () -> tokens.deleteByTokenIn(dead));
                log.info("Removed {} expired push token(s)", dead.size());
            }
        } catch (RuntimeException ex) {
            log.warn("Push delivery failed for notification {}", e.notificationId(), ex);
        }
    }

    /** The vendor organisation always counts as ACTIVE. Grace period (read-only) still gets pushes. */
    private static boolean mayPush(LicenseState state) {
        return state == LicenseState.ACTIVE || state == LicenseState.GRACE;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
