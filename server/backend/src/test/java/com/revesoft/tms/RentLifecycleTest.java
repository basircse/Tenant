package com.revesoft.tms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Property → unit → tenant → agreement → rent → partial payments → overdue → move-out. */
class RentLifecycleTest extends ApiTestBase {

    private static double num(String json, String path) {
        return ((Number) read(json, path)).doubleValue();
    }

    @Test
    void fullRentLifecycle() throws Exception {
        setToday(LocalDate.of(2026, 10, 3));
        Org org = newOrg();
        String t = org.token();
        String property = createProperty(t, "Green View Apartment");
        String unit = createUnit(t, property, "3A", 25000);
        String tenant = createTenant(t, "Abdul Karim", null);

        // Move in.
        String agreement = activateAgreement(t, tenant, unit, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 9, 30));
        assertThat((String) read(get(t, "/api/agreements/" + agreement).ok(), "$.status")).isEqualTo("ACTIVE");
        assertThat((String) read(get(t, "/api/units/" + unit).ok(), "$.status")).isEqualTo("OCCUPIED");
        assertThat((String) read(get(t, "/api/tenants/" + tenant).ok(), "$.tenant.status")).isEqualTo("ACTIVE");

        // A second agreement for the same unit is refused.
        String other = createTenant(t, "Someone Else", null);
        post(t, "/api/agreements", """
                {"tenantId":"%s","unitId":"%s","startDate":"2026-10-01","endDate":"2027-09-30","activate":true}"""
                .formatted(other, unit)).andReturnBodyExpecting(409);

        // Generate rent once; no duplicates.
        assertThat((Integer) read(post(t, "/api/rents/generate", "{\"month\":\"2026-10\"}").ok(), "$.created")).isEqualTo(1);
        assertThat((Integer) read(post(t, "/api/rents/generate", "{\"month\":\"2026-10\"}").ok(), "$.created")).isZero();

        String list = get(t, "/api/rents?month=2026-10").ok();
        String invoice = read(list, "$.items[0].id");
        assertThat(num(list, "$.items[0].totalAmount")).isEqualTo(30000.0);
        assertThat((String) read(list, "$.items[0].dueDate")).isEqualTo("2026-10-05");

        // Partial payment.
        String receipt = post(t, "/api/payments", """
                {"invoiceId":"%s","amount":20000,"method":"CASH"}""".formatted(invoice)).created();
        assertThat((String) read(receipt, "$.receiptNo")).isEqualTo("RCPT-000001");
        assertThat(num(receipt, "$.balanceAfter")).isEqualTo(10000.0);
        String inv = get(t, "/api/rents/" + invoice).ok();
        assertThat((String) read(inv, "$.invoice.status")).isEqualTo("PARTIALLY_PAID");
        assertThat(num(inv, "$.invoice.dueAmount")).isEqualTo(10000.0);

        // Guards: overpayment and missing reference for mobile banking.
        post(t, "/api/payments", """
                {"invoiceId":"%s","amount":15000,"method":"CASH"}""".formatted(invoice)).andReturnBodyExpecting(400);
        post(t, "/api/payments", """
                {"invoiceId":"%s","amount":10000,"method":"BKASH"}""".formatted(invoice)).andReturnBodyExpecting(400);

        // Pay the rest.
        String second = post(t, "/api/payments", """
                {"invoiceId":"%s","amount":10000,"method":"BKASH","referenceNo":"TX123"}""".formatted(invoice)).created();
        assertThat((String) read(get(t, "/api/rents/" + invoice).ok(), "$.invoice.status")).isEqualTo("PAID");
        assertThat((List<?>) read(get(t, "/api/dues").ok(), "$.items")).isEmpty();

        // Voiding a payment re-opens the balance.
        String payments = get(t, "/api/payments").ok();
        String bkashPayment = ((List<String>) read(payments, "$.items[?(@.method == 'BKASH')].id")).get(0);
        assertThat((String) read(second, "$.receiptNo")).isEqualTo("RCPT-000002");
        post(t, "/api/payments/" + bkashPayment + "/void", "{\"reason\":\"Bounced\"}").ok();
        String dues = get(t, "/api/dues").ok();
        assertThat(num(dues, "$.outstanding")).isEqualTo(10000.0);

        // After the due date the invoice becomes overdue.
        setToday(LocalDate.of(2026, 10, 10));
        post(t, "/api/rents/generate", "{\"month\":\"2026-10\"}").ok();
        inv = get(t, "/api/rents/" + invoice).ok();
        assertThat((String) read(inv, "$.invoice.status")).isEqualTo("OVERDUE");
        assertThat((Integer) read(inv, "$.invoice.daysOverdue")).isEqualTo(5);

        // An invoice with payments cannot be cancelled.
        post(t, "/api/rents/" + invoice + "/cancel", "{\"reason\":\"x\"}").andReturnBodyExpecting(409);

        // Move out.
        post(t, "/api/agreements/" + agreement + "/terminate", "{\"reason\":\"Moved out\"}").ok();
        assertThat((String) read(get(t, "/api/units/" + unit).ok(), "$.status")).isEqualTo("AVAILABLE");
        assertThat((String) read(get(t, "/api/tenants/" + tenant).ok(), "$.tenant.status")).isEqualTo("PREVIOUS");

        // Dashboard and audit reflect the activity.
        String dashboard = get(t, "/api/dashboard").ok();
        assertThat(num(dashboard, "$.expectedRent")).isEqualTo(30000.0);
        assertThat(num(dashboard, "$.collectedRent")).isEqualTo(20000.0);
        List<String> actions = read(get(t, "/api/audit").ok(), "$[*].action");
        assertThat(actions).contains("Payment Entry", "Payment Modification", "Generated Rent", "Terminated Agreement");
    }

    @Test
    void unitWithHistoryCannotBeDeleted() throws Exception {
        Org org = newOrg();
        String t = org.token();
        String property = createProperty(t, "P");
        String freeUnit = createUnit(t, property, "1A", 10000);
        String usedUnit = createUnit(t, property, "1B", 10000);
        activateAgreement(t, createTenant(t, "T", null), usedUnit, LocalDate.now(), LocalDate.now().plusYears(1));
        delete(t, "/api/units/" + usedUnit).andReturnBodyExpecting(409);
        delete(t, "/api/units/" + freeUnit).andReturnBodyExpecting(204);
        // Duplicate unit numbers in one property are rejected.
        post(t, "/api/properties/" + property + "/units", """
                {"floor":"1","unitNo":"1b","monthlyRent":5000}""").andReturnBodyExpecting(409);
        // Occupied property cannot be deactivated.
        post(t, "/api/properties/" + property + "/status", "{\"status\":\"INACTIVE\"}").andReturnBodyExpecting(400);
    }
}
