package com.revesoft.tms;

import static org.assertj.core.api.Assertions.assertThat;

import com.revesoft.tms.jobs.DailyJobs;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DailyJobsTest extends ApiTestBase {

    @Autowired
    DailyJobs jobs;

    private void runJobs(Org org) {
        jobs.runFor(tx.asRoot(() -> orgs.findById(org.id()).orElseThrow()));
    }

    @Test
    void agreementsExpireAndUnitsAreReleased() throws Exception {
        setToday(LocalDate.of(2026, 10, 3));
        Org org = newOrg();
        String t = org.token();
        String unit = createUnit(t, createProperty(t, "P"), "2A", 20000);
        String tenant = createTenant(t, "Hasan", null);
        String agreement = activateAgreement(t, tenant, unit, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 20));

        // Ends within the 30-day alert window.
        assertThat((String) read(get(t, "/api/agreements/" + agreement).ok(), "$.status")).isEqualTo("EXPIRING");
        List<String> titles = read(get(t, "/api/notifications").ok(), "$.items[*].title");
        assertThat(titles).contains("Agreement expiring soon");

        // The nightly job generates this month's rent automatically.
        runJobs(org);
        assertThat((List<?>) read(get(t, "/api/rents?month=2026-10").ok(), "$.items")).hasSize(1);
        runJobs(org);
        assertThat((List<?>) read(get(t, "/api/rents?month=2026-10").ok(), "$.items")).hasSize(1);

        // After the end date the agreement expires and the tenant moves out.
        setToday(LocalDate.of(2026, 10, 25));
        runJobs(org);
        assertThat((String) read(get(t, "/api/agreements/" + agreement).ok(), "$.status")).isEqualTo("EXPIRED");
        assertThat((String) read(get(t, "/api/units/" + unit).ok(), "$.status")).isEqualTo("AVAILABLE");
        assertThat((String) read(get(t, "/api/tenants/" + tenant).ok(), "$.tenant.status")).isEqualTo("PREVIOUS");

        // Renewing brings the tenant back.
        String renewed = post(t, "/api/agreements/" + agreement + "/renew", """
                {"newEndDate":"2027-10-20","monthlyRent":22000}""").ok();
        assertThat((String) read(renewed, "$.startDate")).isEqualTo("2026-10-21");
        assertThat((String) read(renewed, "$.status")).isEqualTo("ACTIVE");
        assertThat((String) read(get(t, "/api/units/" + unit).ok(), "$.status")).isEqualTo("OCCUPIED");
    }

    @Test
    void rentDueRemindersAndOverdueAlertsReachTenantAndStaffOnce() throws Exception {
        setToday(LocalDate.of(2026, 10, 1));
        Org org = newOrg();
        String admin = org.token();
        String unit = createUnit(admin, createProperty(admin, "P"), "3A", 12000);
        String tenantLogin = uniq("ten");
        String tenant = createTenant(admin, "Selim", tenantLogin);
        activateAgreement(admin, tenant, unit, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 9, 30));
        String t = login(tenantLogin, PASSWORD);

        // Rent is generated on the 1st, due on the 5th: not yet within the 3-day window.
        runJobs(org);
        assertThat(titles(t)).contains("Rent generated").doesNotContain("Rent due");
        assertThat(titles(admin)).doesNotContain("Rent due soon");

        // Three days before the due date both sides are reminded, once.
        setToday(LocalDate.of(2026, 10, 2));
        runJobs(org);
        runJobs(org);
        assertThat(titles(t)).filteredOn("Rent due"::equals).hasSize(1);
        assertThat(titles(admin)).filteredOn("Rent due soon"::equals).hasSize(1);

        // After the due date: overdue alerts, once.
        setToday(LocalDate.of(2026, 10, 6));
        runJobs(org);
        runJobs(org);
        assertThat(titles(t)).filteredOn("Payment overdue"::equals).hasSize(1);
        assertThat(titles(admin)).filteredOn("Overdue rent"::equals).hasSize(1);
        assertThat((String) read(get(admin, "/api/notifications").ok(), "$.items[0].body")).contains("Selim");
    }

    private List<String> titles(String token) throws Exception {
        return read(get(token, "/api/notifications").ok(), "$.items[*].title");
    }
}
