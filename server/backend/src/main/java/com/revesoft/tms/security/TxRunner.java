package com.revesoft.tms.security;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs work in a new transaction bound to a specific organisation (or ROOT). */
@Component
public class TxRunner {

    private final TransactionTemplate tx;

    public TxRunner(PlatformTransactionManager tm) {
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T asRoot(Supplier<T> work) {
        return inOrg(TenantContext.ROOT, work);
    }

    public <T> T inOrg(UUID orgId, Supplier<T> work) {
        return TenantContext.callAs(orgId, () -> tx.execute(status -> work.get()));
    }

    /** Runs as a given user identity (for audit/role checks) inside that user's organisation. */
    public <T> T as(AuthUser user, Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new SystemAuthentication(user));
        SecurityContextHolder.setContext(ctx);
        try {
            return inOrg(user.orgId(), work);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
