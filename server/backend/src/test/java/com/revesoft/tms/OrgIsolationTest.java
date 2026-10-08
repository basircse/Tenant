package com.revesoft.tms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One landlord must never see or change another landlord's data. */
class OrgIsolationTest extends ApiTestBase {

    @Test
    void organisationsCannotSeeEachOthersData() throws Exception {
        Org a = newOrg();
        Org b = newOrg();

        String propertyA = createProperty(a.token(), "Green View");
        String unitA = createUnit(a.token(), propertyA, "1A", 20000);
        String tenantA = createTenant(a.token(), "Abdul Karim", null);
        String agreementA = activateAgreement(a.token(), tenantA, unitA, LocalDate.now(), LocalDate.now().plusYears(1));

        // Lists are empty for B.
        assertThat((List<?>) read(get(b.token(), "/api/properties").ok(), "$")).isEmpty();
        assertThat((List<?>) read(get(b.token(), "/api/tenants").ok(), "$")).isEmpty();
        assertThat((List<?>) read(get(b.token(), "/api/agreements").ok(), "$")).isEmpty();
        assertThat((List<?>) read(get(b.token(), "/api/rents").ok(), "$.items")).isEmpty();
        assertThat((Integer) read(get(b.token(), "/api/dashboard").ok(), "$.units")).isZero();

        // Direct access by id looks like "not found".
        get(b.token(), "/api/properties/" + propertyA).andReturnBodyExpecting(404);
        get(b.token(), "/api/units/" + unitA).andReturnBodyExpecting(404);
        get(b.token(), "/api/tenants/" + tenantA).andReturnBodyExpecting(404);
        get(b.token(), "/api/agreements/" + agreementA).andReturnBodyExpecting(404);
        put(b.token(), "/api/properties/" + propertyA, """
                {"name":"Hacked","type":"SHOP","address":"x","city":"x","floors":1}""").andReturnBodyExpecting(404);
        post(b.token(), "/api/agreements/" + agreementA + "/terminate", "{\"reason\":\"x\"}").andReturnBodyExpecting(404);

        // B cannot use A's unit or tenant in its own agreement.
        String tenantB = createTenant(b.token(), "Rahim", null);
        post(b.token(), "/api/agreements", """
                {"tenantId":"%s","unitId":"%s","startDate":"%s","endDate":"%s","activate":true}"""
                .formatted(tenantB, unitA, LocalDate.now(), LocalDate.now().plusYears(1))).andReturnBodyExpecting(404);

        // A's data is untouched.
        assertThat((String) read(get(a.token(), "/api/properties/" + propertyA).ok(), "$.property.name"))
                .isEqualTo("Green View");
    }

    @Test
    void codesAreNumberedPerOrganisation() throws Exception {
        Org a = newOrg();
        Org b = newOrg();
        createProperty(a.token(), "A1");
        createProperty(a.token(), "A2");
        createProperty(b.token(), "B1");
        assertThat((List<String>) read(get(a.token(), "/api/properties").ok(), "$[*].code"))
                .containsExactlyInAnyOrder("P-1001", "P-1002");
        assertThat((List<String>) read(get(b.token(), "/api/properties").ok(), "$[*].code"))
                .containsExactly("P-1001");
    }

    @Test
    void sameTenantMobileMayExistInTwoOrganisations() throws Exception {
        Org a = newOrg();
        Org b = newOrg();
        String json = """
                {"name":"Shared Person","mobile":"01912345678","nid":"%s"}""";
        post(a.token(), "/api/tenants", json.formatted("NID-SAME")).created();
        post(b.token(), "/api/tenants", json.formatted("NID-SAME")).created();
        // ...but not twice in the same organisation.
        post(a.token(), "/api/tenants", json.formatted("NID-OTHER")).andReturnBodyExpecting(409);
    }
}
