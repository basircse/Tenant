package com.revesoft.tms;

import com.jayway.jsonpath.JsonPath;
import com.revesoft.tms.common.BusinessClock;
import com.revesoft.tms.org.Organization;
import com.revesoft.tms.org.OrganizationRepository;
import com.revesoft.tms.security.TxRunner;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@SpringBootTest(properties = {"tms.jobs.run-on-startup=false", "tms.vendor.username=" + ApiTestBase.VENDOR,
        "tms.vendor.password=" + ApiTestBase.PASSWORD, "tms.storage.dir=" + ApiTestBase.STORAGE_DIR})
@AutoConfigureMockMvc
@Import(EmbeddedDb.class)
public abstract class ApiTestBase {

    protected static final String PASSWORD = "Secret123";
    protected static final String VENDOR = "vendor-test";
    protected static final String STORAGE_DIR = "target/test-files";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected OrganizationRepository orgs;

    @Autowired
    protected TxRunner tx;

    @Autowired
    protected BusinessClock clock;

    @AfterEach
    void resetClock() {
        clock.setClock(Clock.system(ZoneId.of("Asia/Dhaka")));
    }

    protected void setToday(LocalDate date) {
        ZoneId zone = ZoneId.of("Asia/Dhaka");
        clock.setClock(Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone));
    }

    protected static String uniq(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A registered, activated organisation and its admin session. */
    protected record Org(UUID id, String adminUsername, String token) {
    }

    /** Registers an organisation and leaves it PENDING (waiting for vendor approval). */
    protected Org signupPending() throws Exception {
        String username = uniq("admin");
        String body = request(MockMvcRequestBuilders.post("/api/auth/signup"), null, """
                {"organizationName":"%s","name":"Owner","username":"%s","password":"%s",
                 "mobile":"017%08d","email":"%s@example.com"}"""
                .formatted(uniq("Estate "), username, PASSWORD, (int) (Math.random() * 1e8), username))
                .andReturnBodyExpecting(201);
        UUID orgId = UUID.fromString(JsonPath.read(body, "$.organizationId"));
        return new Org(orgId, username, login(username, PASSWORD));
    }

    protected String vendorToken() throws Exception {
        return login(VENDOR, PASSWORD);
    }

    /** A registered organisation, activated directly (no expiry, no limits) for non-licensing tests. */
    protected Org newOrg() throws Exception {
        Org org = signupPending();
        tx.asRoot(() -> {
            Organization o = orgs.findById(org.id()).orElseThrow();
            o.setStatus(Organization.Status.ACTIVE);
            return null;
        });
        return org;
    }

    protected String login(String username, String password) throws Exception {
        return login(username, password, "device-" + UUID.randomUUID());
    }

    protected String login(String username, String password, String deviceKey) throws Exception {
        return JsonPath.read(loginCall(username, password, deviceKey).andReturnBodyExpecting(200), "$.accessToken");
    }

    protected Call loginCall(String username, String password, String deviceKey) {
        return request(MockMvcRequestBuilders.post("/api/auth/login"), null, """
                {"identifier":"%s","password":"%s","deviceKey":"%s","deviceName":"Test phone","platform":"android"}"""
                .formatted(username, password, deviceKey));
    }

    // ------------------------------------------------------------ HTTP helpers

    protected Call get(String token, String url) {
        return request(MockMvcRequestBuilders.get(url), token, null);
    }

    protected Call post(String token, String url, String json) {
        return request(MockMvcRequestBuilders.post(url), token, json);
    }

    protected Call put(String token, String url, String json) {
        return request(MockMvcRequestBuilders.put(url), token, json);
    }

    protected Call delete(String token, String url) {
        return request(MockMvcRequestBuilders.delete(url), token, null);
    }

    /** Multipart POST with one file part plus form fields given as name/value pairs. */
    protected Call upload(String token, String url, MockMultipartFile file, String... fields) {
        var builder = MockMvcRequestBuilders.multipart(url).file(file);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            builder.param(fields[i], fields[i + 1]);
        }
        return request(builder, token, null);
    }

    /** GET returning the raw response (downloads); asserts the status. */
    protected MockHttpServletResponse download(String token, String url, int status) throws Exception {
        MockHttpServletResponse response = get(token, url).perform().andReturn().getResponse();
        if (response.getStatus() != status) {
            throw new AssertionError("Expected HTTP " + status + " but got " + response.getStatus() + ": "
                    + response.getContentAsString(StandardCharsets.UTF_8));
        }
        return response;
    }

    private Call request(AbstractMockHttpServletRequestBuilder<?> builder, String token, String json) {
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (json != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return new Call(builder);
    }

    protected final class Call {
        private final AbstractMockHttpServletRequestBuilder<?> builder;

        Call(AbstractMockHttpServletRequestBuilder<?> builder) {
            this.builder = builder;
        }

        public ResultActions perform() throws Exception {
            return mvc.perform(builder);
        }

        /** Performs the call, asserts the status and returns the body. */
        public String andReturnBodyExpecting(int status) throws Exception {
            var result = mvc.perform(builder).andReturn();
            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (result.getResponse().getStatus() != status) {
                throw new AssertionError("Expected HTTP " + status + " but got " + result.getResponse().getStatus()
                        + ": " + body);
            }
            return body;
        }

        public String ok() throws Exception {
            return andReturnBodyExpecting(200);
        }

        public String created() throws Exception {
            return andReturnBodyExpecting(201);
        }
    }

    protected static <T> T read(String json, String path) {
        return JsonPath.read(json, path);
    }

    // ------------------------------------------------------------ Fixtures

    protected String createProperty(String token, String name) throws Exception {
        return read(post(token, "/api/properties", """
                {"name":"%s","type":"APARTMENT","address":"Road 1","city":"Dhaka","floors":3}""".formatted(name))
                .created(), "$.property.id");
    }

    protected String createUnit(String token, String propertyId, String unitNo, int rent) throws Exception {
        return read(post(token, "/api/properties/" + propertyId + "/units", """
                {"floor":"1","unitNo":"%s","monthlyRent":%d,"serviceCharge":3000,"utilityCharge":2000,
                 "securityDeposit":%d}""".formatted(unitNo, rent, rent * 2)).created(), "$.id");
    }

    protected String createTenant(String token, String name, String loginUsername) throws Exception {
        String login = loginUsername == null ? "" : """
                ,"login":{"username":"%s","password":"%s"}""".formatted(loginUsername, PASSWORD);
        return read(post(token, "/api/tenants", """
                {"name":"%s","mobile":"018%08d","nid":"%s"%s}"""
                .formatted(name, (int) (Math.random() * 1e8), uniq("NID"), login)).created(), "$.tenant.id");
    }

    protected String activateAgreement(String token, String tenantId, String unitId, LocalDate start,
                                       LocalDate end) throws Exception {
        return read(post(token, "/api/agreements", """
                {"tenantId":"%s","unitId":"%s","startDate":"%s","endDate":"%s","dueDay":5,"activate":true}"""
                .formatted(tenantId, unitId, start, end)).created(), "$.id");
    }
}
