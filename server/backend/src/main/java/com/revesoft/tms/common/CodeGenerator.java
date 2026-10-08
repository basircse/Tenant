package com.revesoft.tms.common;

import com.revesoft.tms.security.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Atomic per-organisation counters for human readable codes such as P-1001 or RCPT-000001. */
@Component
public class CodeGenerator {

    @PersistenceContext
    private EntityManager em;

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String prefix, long start, int pad) {
        // MySQL idiom for an atomic counter: LAST_INSERT_ID(expr) stores the new value for this
        // connection, so the read below returns exactly the value this transaction claimed.
        em.createNativeQuery("""
                        INSERT INTO code_counters (org_id, prefix, counter_value) VALUES (:org, :prefix, LAST_INSERT_ID(:start))
                        ON DUPLICATE KEY UPDATE counter_value = LAST_INSERT_ID(counter_value + 1)""")
                .setParameter("org", TenantContext.requireOrg().toString())
                .setParameter("prefix", prefix)
                .setParameter("start", start)
                .executeUpdate();
        Number n = (Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult();
        String digits = String.valueOf(n.longValue());
        return prefix + "-" + (digits.length() < pad ? "0".repeat(pad - digits.length()) + digits : digits);
    }

    public String next(String prefix) {
        return next(prefix, 1001, 0);
    }
}
