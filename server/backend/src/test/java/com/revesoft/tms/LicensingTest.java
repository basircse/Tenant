package com.revesoft.tms;

import static org.assertj.core.api.Assertions.assertThat;

import com.revesoft.tms.jobs.DailyJobs;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Vendor approval, subscription expiry with read-only grace, device and unit limits. */
class LicensingTest extends ApiTestBase {

    @Autowired
    DailyJobs jobs;

    private String approve(String vendor, Org org, String expiresOn, Integer maxUnits, Integer maxDevices)
            throws Exception {
        return post(vendor, "/api/vendor/organizations/" + org.id() + "/approve", """
                {"expiresOn":%s,"maxUnits":%s,"maxDevices":%s,"plan":"Basic","note":"First year"}"""
                .formatted(expiresOn == null ? "null" : "\"" + expiresOn + "\"", maxUnits, maxDevices)).ok();
    }

    private static String code(String problem) {
        return read(problem, "$.code");
    }

    @Test
    void newRegistrationIsBlockedUntilTheVendorApproves() throws Exception {
        Org org = signupPending();
        String t = org.token();

        // Signing in works so the app can explain the situation...
        assertThat((String) read(get(t, "/api/auth/me").ok(), "$.licenseState")).isEqualTo("PENDING");
        assertThat((String) read(get(t, "/api/license").ok(), "$.state")).isEqualTo("PENDING");
        // ...but the business API is closed.
        assertThat(code(get(t, "/api/properties").andReturnBodyExpecting(403))).isEqualTo("LICENSE_PENDING");
        assertThat(code(post(t, "/api/properties", "{}").andReturnBodyExpecting(403))).isEqualTo("LICENSE_PENDING");

        // The vendor sees the request and is notified.
        String vendor = vendorToken();
        List<String> pending = read(get(vendor, "/api/vendor/organizations?state=PENDING").ok(), "$[*].id");
        assertThat(pending).contains(org.id().toString());
        assertThat((Integer) read(get(vendor, "/api/vendor/summary").ok(), "$.pending")).isPositive();
        List<String> vendorAlerts = read(get(vendor, "/api/notifications").ok(), "$.items[*].title");
        assertThat(vendorAlerts).contains("New registration");

        // Landlords cannot use the vendor console.
        get(t, "/api/vendor/organizations").andReturnBodyExpecting(403);

        String approved = approve(vendor, org, LocalDate.now().plusYears(1).toString(), 50, 3);
        assertThat((String) read(approved, "$.organization.state")).isEqualTo("ACTIVE");
        assertThat((String) read(approved, "$.organization.plan")).isEqualTo("Basic");
        assertThat((List<String>) read(approved, "$.history[*].action")).contains("Registered", "Approved");

        // Now the landlord can work, and was told.
        createProperty(t, "Green View");
        assertThat((List<String>) read(get(t, "/api/notifications").ok(), "$.items[*].title")).contains("Account approved");
        String status = get(t, "/api/license").ok();
        assertThat((String) read(status, "$.state")).isEqualTo("ACTIVE");
        assertThat((Integer) read(status, "$.maxUnits")).isEqualTo(50);
    }

    @Test
    void rejectedRegistrationStaysBlocked() throws Exception {
        Org org = signupPending();
        String vendor = vendorToken();
        post(vendor, "/api/vendor/organizations/" + org.id() + "/reject", "{\"reason\":\"Fake details\"}").ok();
        assertThat(code(get(org.token(), "/api/properties").andReturnBodyExpecting(403))).isEqualTo("LICENSE_REJECTED");
    }

    @Test
    void unitLimitIsEnforced() throws Exception {
        Org org = signupPending();
        approve(vendorToken(), org, null, 2, null);
        String t = org.token();
        String property = createProperty(t, "Small plan");
        String first = createUnit(t, property, "1A", 10000);
        createUnit(t, property, "1B", 10000);
        String third = post(t, "/api/properties/" + property + "/units", """
                {"floor":"1","unitNo":"1C","monthlyRent":10000}""").andReturnBodyExpecting(403);
        assertThat(code(third)).isEqualTo("UNIT_LIMIT");

        // Inactive units do not count; re-activating one is checked again.
        put(t, "/api/units/" + first, """
                {"floor":"1","unitNo":"1A","monthlyRent":10000,"status":"INACTIVE"}""").ok();
        createUnit(t, property, "1C", 10000);
        assertThat(code(put(t, "/api/units/" + first, """
                {"floor":"1","unitNo":"1A","monthlyRent":10000,"status":"AVAILABLE"}""").andReturnBodyExpecting(403)))
                .isEqualTo("UNIT_LIMIT");
    }

    @Test
    void deviceLimitApprovalRemovalAndBlocking() throws Exception {
        Org org = signupPending(); // signs in from one device during sign-up
        String vendor = vendorToken();
        approve(vendor, org, null, null, 2);
        String admin = org.adminUsername();

        // Second device fits the limit of two; the third waits.
        String phoneB = login(admin, PASSWORD, "phone-B");
        String refused = loginCall(admin, PASSWORD, "phone-C").andReturnBodyExpecting(403);
        assertThat(code(refused)).isEqualTo("DEVICE_PENDING");
        // Trying again does not help while the limit is reached.
        loginCall(admin, PASSWORD, "phone-C").andReturnBodyExpecting(403);

        List<String> statuses = read(get(phoneB, "/api/devices").ok(), "$[*].status");
        assertThat(statuses).containsExactlyInAnyOrder("APPROVED", "APPROVED", "PENDING");
        assertThat((List<String>) read(get(phoneB, "/api/notifications").ok(), "$.items[*].title"))
                .contains("New device waiting");

        // The device in use cannot remove itself...
        delete(phoneB, "/api/devices/" + deviceOf(phoneB)).andReturnBodyExpecting(400);
        // ...but the admin frees a slot by removing the sign-up device; phone C can now sign in.
        List<String> approvedIds = read(get(phoneB, "/api/devices").ok(), "$[?(@.status == 'APPROVED')].id");
        String signupDevice = approvedIds.stream().filter(id -> !id.equals(deviceOf(phoneB))).findFirst().orElseThrow();
        delete(phoneB, "/api/devices/" + signupDevice).andReturnBodyExpecting(204);
        String phoneC = login(admin, PASSWORD, "phone-C");

        // The vendor blocks phone C: its token and refresh stop working.
        String phoneCId = deviceOf(phoneC);
        post(vendor, "/api/vendor/devices/" + phoneCId + "/block", "").ok();
        assertThat(code(get(phoneC, "/api/properties").andReturnBodyExpecting(403))).isEqualTo("DEVICE_BLOCKED");
        assertThat(code(loginCall(admin, PASSWORD, "phone-C").andReturnBodyExpecting(403))).isEqualTo("DEVICE_BLOCKED");

        // A blocked device frees its slot: phone D fits (B + D = 2), phone E waits.
        login(admin, PASSWORD, "phone-D");
        assertThat(code(loginCall(admin, PASSWORD, "phone-E").andReturnBodyExpecting(403))).isEqualTo("DEVICE_PENDING");
        // The vendor can approve a device beyond the limit.
        String pendingE = ((List<String>) read(get(vendor, "/api/vendor/organizations/" + org.id()).ok(),
                "$.devices[?(@.status == 'PENDING')].id")).get(0);
        post(vendor, "/api/vendor/devices/" + pendingE + "/approve", "").ok();
        login(admin, PASSWORD, "phone-E");

        // Staff must identify the device; tenants need not.
        loginCall(admin, PASSWORD, "").andReturnBodyExpecting(400);
    }

    /** The device id is the token's "dev" claim. */
    private static String deviceOf(String token) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return read(payload, "$.dev");
    }

    @Test
    void expiryWarnsThenGoesReadOnlyThenLocksUntilRenewed() throws Exception {
        setToday(LocalDate.of(2026, 10, 3));
        Org org = signupPending();
        String vendor = vendorToken();
        approve(vendor, org, "2026-10-31", null, null);
        String t = org.token();
        String property = createProperty(t, "P");
        String unit = createUnit(t, property, "1A", 10000);
        String tenantLogin = uniq("ten");
        String tenant = createTenant(t, "Karim", tenantLogin);
        activateAgreement(t, tenant, unit, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 9, 30));
        String tenantToken = login(tenantLogin, PASSWORD);

        // 11 days before expiry: reminder.
        setToday(LocalDate.of(2026, 10, 20));
        jobs.runFor(tx.asRoot(() -> orgs.findById(org.id()).orElseThrow()));
        assertThat((List<String>) read(get(t, "/api/notifications").ok(), "$.items[*].title"))
                .contains("Subscription expiring");

        // Expired, inside the 7-day grace period: read-only.
        setToday(LocalDate.of(2026, 11, 3));
        get(t, "/api/properties").ok();
        assertThat(code(post(t, "/api/properties", """
                {"name":"New","type":"SHOP","address":"x","city":"x","floors":1}""").andReturnBodyExpecting(403)))
                .isEqualTo("LICENSE_READ_ONLY");
        String license = get(t, "/api/license").ok();
        assertThat((String) read(license, "$.state")).isEqualTo("GRACE");
        assertThat((Boolean) read(license, "$.readOnly")).isTrue();
        assertThat((String) read(license, "$.graceEndsOn")).isEqualTo("2026-11-07");
        get(tenantToken, "/api/me/home").ok();

        // Beyond the grace period: locked for staff and tenants.
        setToday(LocalDate.of(2026, 11, 10));
        assertThat(code(get(t, "/api/properties").andReturnBodyExpecting(403))).isEqualTo("LICENSE_EXPIRED");
        assertThat(code(get(tenantToken, "/api/me/home").andReturnBodyExpecting(403))).isEqualTo("LICENSE_EXPIRED");

        // The landlord asks for renewal; the vendor extends.
        post(t, "/api/license/renewal-request", "{\"message\":\"Please renew for 1 year\"}").andReturnBodyExpecting(202);
        assertThat((List<String>) read(get(vendor, "/api/notifications").ok(), "$.items[*].title"))
                .contains("Renewal requested");
        String extended = post(vendor, "/api/vendor/organizations/" + org.id() + "/extend", """
                {"expiresOn":"2027-10-31","note":"Paid by bKash"}""").ok();
        assertThat((String) read(extended, "$.organization.expiresOn")).isEqualTo("2027-10-31");
        assertThat((List<String>) read(extended, "$.history[*].action"))
                .contains("Approved", "Renewal Requested", "Subscription Extended");

        get(t, "/api/properties").ok();
        get(tenantToken, "/api/me/home").ok();
        assertThat((List<String>) read(get(t, "/api/notifications").ok(), "$.items[*].title"))
                .contains("Subscription renewed");
    }

    @Test
    void suspensionBlocksEveryoneUntilReactivated() throws Exception {
        Org org = signupPending();
        String vendor = vendorToken();
        approve(vendor, org, null, null, null);
        String t = org.token();
        String unit = createUnit(t, createProperty(t, "P"), "1A", 10000);
        String tenantLogin = uniq("ten");
        activateAgreement(t, createTenant(t, "Karim", tenantLogin), unit, LocalDate.now(), LocalDate.now().plusYears(1));
        String tenantToken = login(tenantLogin, PASSWORD);

        post(vendor, "/api/vendor/organizations/" + org.id() + "/suspend", "{\"reason\":\"Unpaid\"}").ok();
        assertThat(code(get(t, "/api/dashboard").andReturnBodyExpecting(403))).isEqualTo("LICENSE_SUSPENDED");
        assertThat(code(get(tenantToken, "/api/me/home").andReturnBodyExpecting(403))).isEqualTo("LICENSE_SUSPENDED");

        post(vendor, "/api/vendor/organizations/" + org.id() + "/reactivate", "{\"note\":\"Paid\"}").ok();
        get(t, "/api/dashboard").ok();
        get(tenantToken, "/api/me/home").ok();
    }
}
