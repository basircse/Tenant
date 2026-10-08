package com.revesoft.tms;

import static org.assertj.core.api.Assertions.assertThat;

import com.revesoft.tms.common.RequestLoggingFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Every response carries the request id that its log lines are tagged with. */
class RequestLoggingTest extends ApiTestBase {

    @Test
    void responsesCarryARequestId() throws Exception {
        String generated = mvc.perform(MockMvcRequestBuilders.get("/api/app/releases/admin")).andReturn()
                .getResponse().getHeader(RequestLoggingFilter.HEADER);
        assertThat(generated).matches("[0-9a-f-]{13}");

        // A client's own id is kept (so app and server logs can be matched) ...
        String echoed = mvc.perform(MockMvcRequestBuilders.get("/api/app/releases/admin")
                .header(RequestLoggingFilter.HEADER, "app-1234abcd")).andReturn()
                .getResponse().getHeader(RequestLoggingFilter.HEADER);
        assertThat(echoed).isEqualTo("app-1234abcd");

        // ... unless it could be used to forge log lines.
        String replaced = mvc.perform(MockMvcRequestBuilders.get("/api/app/releases/admin")
                .header(RequestLoggingFilter.HEADER, "x\nFAKE LOG LINE")).andReturn()
                .getResponse().getHeader(RequestLoggingFilter.HEADER);
        assertThat(replaced).doesNotContain("FAKE").matches("[0-9a-f-]{13}");
    }
}
