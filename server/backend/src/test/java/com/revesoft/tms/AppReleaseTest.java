package com.revesoft.tms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The update checker is public and reads the configured releases. */
@TestPropertySource(properties = {"tms.apps.admin.latest-version=1.2.0", "tms.apps.admin.min-version=1.0.0",
        "tms.apps.admin.download-url=https://example.com/tms-admin.apk"})
class AppReleaseTest extends ApiTestBase {

    @Test
    void releasesArePublic() throws Exception {
        String admin = get(null, "/api/app/releases/admin").ok();
        assertThat((String) read(admin, "$.latestVersion")).isEqualTo("1.2.0");
        assertThat((String) read(admin, "$.downloadUrl")).isEqualTo("https://example.com/tms-admin.apk");
        // Nothing configured for the tenant app: no update offered.
        String tenant = get(null, "/api/app/releases/tenant").ok();
        assertThat(tenant).doesNotContain("latestVersion");
        get(null, "/api/app/releases/other").andReturnBodyExpecting(404);
    }
}
