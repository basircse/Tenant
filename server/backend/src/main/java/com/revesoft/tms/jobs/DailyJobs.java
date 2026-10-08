package com.revesoft.tms.jobs;

import com.revesoft.tms.agreement.AgreementService;
import com.revesoft.tms.common.BusinessClock;
import com.revesoft.tms.common.RequestLoggingFilter;
import com.revesoft.tms.license.LicenseService;
import com.revesoft.tms.license.LicenseState;
import com.revesoft.tms.org.Organization;
import com.revesoft.tms.org.OrganizationRepository;
import com.revesoft.tms.rent.RentService;
import com.revesoft.tms.security.AuthUser;
import com.revesoft.tms.security.Role;
import com.revesoft.tms.security.TxRunner;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly maintenance for every organisation: agreement expiry, overdue rent, automatic monthly
 * rent generation and the related notifications. Safe to run repeatedly.
 */
@Component
public class DailyJobs {

    private static final Logger log = LoggerFactory.getLogger(DailyJobs.class);

    private final OrganizationRepository orgs;
    private final AgreementService agreements;
    private final RentService rent;
    private final TxRunner tx;
    private final BusinessClock clock;
    private final LicenseService licenses;
    private final boolean runOnStartup;

    public DailyJobs(OrganizationRepository orgs, AgreementService agreements, RentService rent, TxRunner tx,
                     BusinessClock clock, LicenseService licenses,
                     @Value("${tms.jobs.run-on-startup:true}") boolean runOnStartup) {
        this.orgs = orgs;
        this.agreements = agreements;
        this.rent = rent;
        this.tx = tx;
        this.clock = clock;
        this.licenses = licenses;
        this.runOnStartup = runOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        if (runOnStartup) {
            runAll();
        }
    }

    @Scheduled(cron = "${tms.jobs.daily-cron:0 15 0 * * *}", zone = "${tms.zone}")
    public void runAll() {
        // Jobs have no request: give the run its own id in the log context.
        MDC.put(RequestLoggingFilter.REQUEST_ID, "job-" + UUID.randomUUID().toString().substring(0, 8));
        MDC.put(RequestLoggingFilter.USER, "System");
        long start = System.nanoTime();
        int ran = 0;
        int skipped = 0;
        int failed = 0;
        try {
            List<Organization> all = tx.asRoot(orgs::findAll);
            log.info("Daily jobs: starting for {} organisation(s)", all.size());
            for (Organization org : all) {
                LicenseState state = licenses.stateOf(org);
                if (org.isVendor() || state == LicenseState.PENDING || state == LicenseState.REJECTED
                        || state == LicenseState.SUSPENDED) {
                    log.debug("Daily jobs: skipping {} ({}): {}", org.getName(), org.getId(),
                            org.isVendor() ? "vendor" : state);
                    skipped++;
                    continue;
                }
                try {
                    runFor(org);
                    ran++;
                } catch (RuntimeException e) {
                    failed++;
                    log.error("Daily job failed for organisation {} ({})", org.getName(), org.getId(), e);
                }
            }
        } finally {
            log.info("Daily jobs: done in {} ms ({} ran, {} skipped, {} failed)",
                    (System.nanoTime() - start) / 1_000_000, ran, skipped, failed);
            MDC.remove(RequestLoggingFilter.REQUEST_ID);
            MDC.remove(RequestLoggingFilter.USER);
        }
    }

    public void runFor(Organization org) {
        AuthUser system = new AuthUser(null, org.getId(), Role.ADMIN, "System", null);
        String previousOrg = MDC.get(RequestLoggingFilter.ORG);
        MDC.put(RequestLoggingFilter.ORG, org.getId().toString());
        long start = System.nanoTime();
        try {
            tx.as(system, () -> {
                licenses.sendReminders(org);
                agreements.refreshStatuses();
                if (org.isAutoGenerateRent()) {
                    rent.generate(clock.currentMonth(), true);
                } else {
                    rent.refreshStatuses();
                }
                return null;
            });
            log.debug("Daily jobs for {} done in {} ms", org.getName(), (System.nanoTime() - start) / 1_000_000);
        } finally {
            if (previousOrg == null) {
                MDC.remove(RequestLoggingFilter.ORG);
            } else {
                MDC.put(RequestLoggingFilter.ORG, previousOrg);
            }
        }
    }
}
