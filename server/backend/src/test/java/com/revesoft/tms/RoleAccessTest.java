package com.revesoft.tms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Managers see only assigned properties; tenants only their own records. */
class RoleAccessTest extends ApiTestBase {

    @Test
    void managerIsLimitedToAssignedProperties() throws Exception {
        Org org = newOrg();
        String admin = org.token();
        String p1 = createProperty(admin, "Assigned");
        String p2 = createProperty(admin, "Not assigned");
        String manager = uniq("mgr");
        post(admin, "/api/users", """
                {"name":"Mamun","username":"%s","role":"MANAGER","propertyIds":["%s"],"password":"%s"}"""
                .formatted(manager, p1, PASSWORD)).created();
        String m = login(manager, PASSWORD);

        assertThat((List<String>) read(get(m, "/api/properties").ok(), "$[*].id")).containsExactly(p1);
        get(m, "/api/properties/" + p2).andReturnBodyExpecting(403);
        post(m, "/api/properties/" + p2 + "/units", """
                {"floor":"1","unitNo":"X","monthlyRent":1000}""").andReturnBodyExpecting(403);

        // A property the manager creates is assigned to them automatically.
        String p3 = createProperty(m, "Manager's own");
        assertThat((List<String>) read(get(m, "/api/properties").ok(), "$[*].id")).containsExactlyInAnyOrder(p1, p3);

        // Admin-only areas.
        get(m, "/api/users").andReturnBodyExpecting(403);
        get(m, "/api/audit").andReturnBodyExpecting(403);
        put(m, "/api/org", """
                {"name":"x","currency":"$","expiryAlertDays":30,"autoGenerateRent":true}""").andReturnBodyExpecting(403);
    }

    @Test
    void tenantPortalShowsOnlyOwnData() throws Exception {
        setToday(LocalDate.of(2026, 10, 3));
        Org org = newOrg();
        String admin = org.token();
        String property = createProperty(admin, "Green View");
        String unit1 = createUnit(admin, property, "1A", 25000);
        String unit2 = createUnit(admin, property, "1B", 15000);
        String karimLogin = uniq("karim");
        String karim = createTenant(admin, "Abdul Karim", karimLogin);
        String rahim = createTenant(admin, "Rahim", null);
        activateAgreement(admin, karim, unit1, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 9, 30));
        activateAgreement(admin, rahim, unit2, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 9, 30));
        post(admin, "/api/rents/generate", "{\"month\":\"2026-10\"}").ok();

        // Pay Rahim's invoice so a receipt exists that Karim must not see.
        String rahimInvoice = read(get(admin, "/api/rents?tenantId=" + rahim).ok(), "$.items[0].id");
        String rahimReceipt = post(admin, "/api/payments", """
                {"invoiceId":"%s","amount":1000,"method":"CASH"}""".formatted(rahimInvoice)).created();
        String rahimPaymentId = read(get(admin, "/api/payments?tenantId=" + rahim).ok(), "$.items[0].id");

        String k = login(karimLogin, PASSWORD);

        // Staff APIs are closed to tenants.
        get(k, "/api/properties").andReturnBodyExpecting(403);
        get(k, "/api/tenants").andReturnBodyExpecting(403);
        get(k, "/api/rents").andReturnBodyExpecting(403);
        get(k, "/api/payments/" + rahimPaymentId + "/receipt").andReturnBodyExpecting(403);

        // Own data through the portal.
        String home = get(k, "/api/me/home").ok();
        assertThat((String) read(home, "$.tenant.name")).isEqualTo("Abdul Karim");
        assertThat((String) read(home, "$.currentAgreement.unitNo")).isEqualTo("1A");
        assertThat(((Number) read(home, "$.outstanding")).doubleValue()).isEqualTo(30000.0);
        assertThat((List<?>) read(get(k, "/api/me/invoices").ok(), "$.items")).hasSize(1);
        assertThat((List<?>) read(get(k, "/api/me/payments").ok(), "$.items")).isEmpty();
        get(k, "/api/me/payments/" + rahimPaymentId + "/receipt").andReturnBodyExpecting(404);
        assertThat((String) read(rahimReceipt, "$.tenantName")).isEqualTo("Rahim");

        // Tenant raises a maintenance request; staff sees it.
        String request = post(k, "/api/me/maintenance", """
                {"title":"Kitchen sink leaking","type":"PLUMBING","priority":"HIGH","description":"Water under the sink"}""")
                .created();
        assertThat((String) read(request, "$.unitNo")).isEqualTo("1A");
        assertThat((List<String>) read(get(admin, "/api/maintenance").ok(), "$[*].title")).contains("Kitchen sink leaking");

        // Staff moves it forward; the tenant is notified.
        String requestId = read(request, "$.id");
        post(admin, "/api/maintenance/" + requestId + "/status", "{\"status\":\"IN_PROGRESS\"}").andReturnBodyExpecting(400);
        post(admin, "/api/maintenance/" + requestId + "/assign", "{\"assignedTo\":\"Jalal (Plumber)\"}").ok();
        post(admin, "/api/maintenance/" + requestId + "/status", "{\"status\":\"IN_PROGRESS\"}").ok();
        List<String> titles = read(get(k, "/api/notifications").ok(), "$.items[*].title");
        assertThat(titles).contains("Maintenance update", "Rent generated", "Agreement activated");
        assertThat((String) read(get(k, "/api/me/maintenance/" + requestId).ok(), "$.status")).isEqualTo("IN_PROGRESS");
    }
}
