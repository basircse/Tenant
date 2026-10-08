package com.revesoft.tms;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Report JSON, Excel and PDF export, manager scope, and receipt PDFs. */
class ReportTest extends ApiTestBase {

    static final List<String> TYPES = List.of("rent-roll", "collections", "dues", "expenses", "profit-loss",
            "occupancy", "tenants");

    record Fixture(Org org, String propertyA, String propertyB, String tenantLogin, String paymentA, String paymentB) {
    }

    /** Two properties, a tenant in each, October rent generated, one payment and one expense per property. */
    private Fixture fixture() throws Exception {
        setToday(LocalDate.of(2026, 10, 20));
        Org org = newOrg();
        String t = org.token();
        String a = createProperty(t, "Alpha Tower");
        String b = createProperty(t, "Beta House");
        String unitA = createUnit(t, a, "A1", 20000);
        createUnit(t, a, "A2", 15000);
        String unitB = createUnit(t, b, "B1", 10000);
        String tenantLogin = uniq("karim");
        String karim = createTenant(t, "Karim", tenantLogin);
        String rahim = createTenant(t, "Rahim", null);
        activateAgreement(t, karim, unitA, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 9, 30));
        activateAgreement(t, rahim, unitB, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 9, 30));
        post(t, "/api/rents/generate", "{\"month\":\"2026-10\"}").ok();
        String invA = read(get(t, "/api/rents?tenantId=" + karim).ok(), "$.items[0].id");
        String invB = read(get(t, "/api/rents?tenantId=" + rahim).ok(), "$.items[0].id");
        post(t, "/api/payments", """
                {"invoiceId":"%s","amount":10000,"method":"CASH","paymentDate":"2026-10-06"}""".formatted(invA)).created();
        post(t, "/api/payments", """
                {"invoiceId":"%s","amount":5000,"method":"BKASH","referenceNo":"TX1","paymentDate":"2026-10-07"}"""
                .formatted(invB)).created();
        String payA = read(get(t, "/api/payments?tenantId=" + karim).ok(), "$.items[0].id");
        String payB = read(get(t, "/api/payments?tenantId=" + rahim).ok(), "$.items[0].id");
        post(t, "/api/expenses", """
                {"propertyId":"%s","expenseDate":"2026-10-10","category":"Utility","amount":2500}""".formatted(a)).created();
        post(t, "/api/expenses", """
                {"propertyId":"%s","expenseDate":"2026-10-11","category":"Cleaning","amount":1000}""".formatted(b)).created();
        post(t, "/api/expenses", """
                {"expenseDate":"2026-10-12","category":"Tax","amount":700}""").created();
        return new Fixture(org, a, b, tenantLogin, payA, payB);
    }

    @Test
    void everyReportReturnsJsonExcelAndPdf() throws Exception {
        Fixture f = fixture();
        String t = f.org().token();

        List<String> listed = read(get(t, "/api/reports").ok(), "$[*].type");
        assertThat(listed).containsExactlyElementsOf(TYPES);

        for (String type : TYPES) {
            String json = get(t, "/api/reports/" + type + "?month=2026-10").ok();
            assertThat((String) read(json, "$.title")).as(type).isNotBlank();
            assertThat((List<?>) read(json, "$.columns")).as(type).isNotEmpty();
            assertThat((List<?>) read(json, "$.rows")).as(type).isNotEmpty();
            assertThat((Map<?, ?>) read(json, "$.totals")).as(type).isNotEmpty();
            List<String> kinds = read(json, "$.columns[*].type");
            assertThat(kinds).as(type).allMatch(k -> List.of("text", "money", "date", "number", "percent").contains(k));

            MockHttpServletResponse xlsx = download(t, "/api/reports/" + type + "?month=2026-10&format=xlsx", 200);
            byte[] x = xlsx.getContentAsByteArray();
            assertThat(new String(Arrays.copyOf(x, 2), StandardCharsets.US_ASCII)).isEqualTo("PK");
            assertThat(xlsx.getHeader("Content-Disposition")).contains(type + "-2026-10.xlsx");

            MockHttpServletResponse pdf = download(t, "/api/reports/" + type + "?month=2026-10&format=pdf", 200);
            assertThat(new String(Arrays.copyOf(pdf.getContentAsByteArray(), 4), StandardCharsets.US_ASCII))
                    .isEqualTo("%PDF");
            assertThat(pdf.getContentType()).isEqualTo("application/pdf");
            assertThat(pdf.getHeader("Content-Disposition")).contains(type + "-2026-10.pdf");
        }

        // Figures.
        String collections = get(t, "/api/reports/collections?from=2026-10-01&to=2026-10-31").ok();
        assertThat(((Number) read(collections, "$.totals.amount")).doubleValue()).isEqualTo(15000.0);
        assertThat((List<String>) read(collections, "$.summary[*].label")).containsExactly("Cash", "Bkash");
        assertThat((List<?>) read(get(t, "/api/reports/collections?from=2026-10-07&to=2026-10-31").ok(), "$.rows"))
                .hasSize(1);
        String pl = get(t, "/api/reports/profit-loss?month=2026-10").ok();
        assertThat((List<String>) read(pl, "$.rows[*].property"))
                .containsExactly("Alpha Tower", "Beta House", "General (all properties)");
        assertThat(((Number) read(pl, "$.totals.net")).doubleValue()).isEqualTo(15000.0 - 2500 - 1000 - 700);
        String occupancy = get(t, "/api/reports/occupancy").ok();
        assertThat(((Number) read(occupancy, "$.totals.units")).intValue()).isEqualTo(3);
        assertThat(((Number) read(occupancy, "$.totals.occupied")).intValue()).isEqualTo(2);
        String dues = get(t, "/api/reports/dues").ok();
        assertThat(((Number) read(dues, "$.totals.due")).doubleValue()).isEqualTo(25000.0 + 15000 - 15000);
        assertThat(((Number) read(dues, "$.rows[0].daysOverdue")).intValue()).isEqualTo(15);

        // The workbook has a header row and the data.
        byte[] book = download(t, "/api/reports/tenants?format=xlsx", 200).getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(book))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Tenants");
            assertThat(sheet.getLastRowNum()).isGreaterThanOrEqualTo(6);
        }

        get(t, "/api/reports/unknown").andReturnBodyExpecting(404);
        get(t, "/api/reports/dues?format=docx").andReturnBodyExpecting(400);
    }

    @Test
    void managersSeeOnlyTheirPropertiesAndTenantsNothing() throws Exception {
        Fixture f = fixture();
        String admin = f.org().token();
        String manager = uniq("mgr");
        post(admin, "/api/users", """
                {"name":"Mamun","username":"%s","role":"MANAGER","propertyIds":["%s"],"password":"%s"}"""
                .formatted(manager, f.propertyA(), PASSWORD)).created();
        String m = login(manager, PASSWORD);

        assertThat((List<String>) read(get(m, "/api/reports/rent-roll").ok(), "$.rows[*].property"))
                .containsOnly("Alpha Tower").hasSize(2);
        assertThat((List<String>) read(get(m, "/api/reports/collections?month=2026-10").ok(), "$.rows[*].property"))
                .containsExactly("Alpha Tower");
        assertThat((List<String>) read(get(m, "/api/reports/profit-loss?month=2026-10").ok(), "$.rows[*].property"))
                .containsExactly("Alpha Tower");
        assertThat((List<String>) read(get(m, "/api/reports/expenses?month=2026-10").ok(), "$.rows[*].property"))
                .containsExactly("Alpha Tower");
        get(m, "/api/reports/dues?propertyId=" + f.propertyB()).andReturnBodyExpecting(403);
        download(m, "/api/reports/collections?format=pdf&propertyId=" + f.propertyB(), 403);

        // Admin filter by property.
        assertThat((List<String>) read(get(admin, "/api/reports/occupancy?propertyId=" + f.propertyB()).ok(),
                "$.rows[*].property")).containsExactly("Beta House");

        String tenant = login(f.tenantLogin(), PASSWORD);
        get(tenant, "/api/reports/rent-roll").andReturnBodyExpecting(403);
        get(tenant, "/api/reports").andReturnBodyExpecting(403);

        // Another landlord sees none of this data.
        Org other = newOrg();
        assertThat((List<?>) read(get(other.token(), "/api/reports/collections?month=2026-10").ok(), "$.rows")).isEmpty();
        get(other.token(), "/api/reports/rent-roll?propertyId=" + f.propertyA()).andReturnBodyExpecting(404);
    }

    @Test
    void receiptsDownloadAsPdf() throws Exception {
        Fixture f = fixture();
        String admin = f.org().token();
        MockHttpServletResponse staff = download(admin, "/api/payments/" + f.paymentA() + "/receipt.pdf", 200);
        assertThat(new String(Arrays.copyOf(staff.getContentAsByteArray(), 4), StandardCharsets.US_ASCII))
                .isEqualTo("%PDF");
        assertThat(staff.getHeader("Content-Disposition")).contains("receipt-RCPT-").contains(".pdf");

        String tenant = login(f.tenantLogin(), PASSWORD);
        MockHttpServletResponse own = download(tenant, "/api/me/payments/" + f.paymentA() + "/receipt.pdf", 200);
        assertThat(new String(Arrays.copyOf(own.getContentAsByteArray(), 4), StandardCharsets.US_ASCII))
                .isEqualTo("%PDF");
        download(tenant, "/api/me/payments/" + f.paymentB() + "/receipt.pdf", 404);
        download(tenant, "/api/payments/" + f.paymentA() + "/receipt.pdf", 403);
        download(newOrg().token(), "/api/payments/" + f.paymentA() + "/receipt.pdf", 404);
    }
}
