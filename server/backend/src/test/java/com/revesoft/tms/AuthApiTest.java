package com.revesoft.tms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthApiTest extends ApiTestBase {

    @Test
    void loginReturnsTokensAndProfile() throws Exception {
        Org org = newOrg();
        String body = post(null, "/api/auth/login", """
                {"identifier":"%s","password":"%s","deviceKey":"test-device"}""".formatted(org.adminUsername(), PASSWORD)).ok();
        assertThat((String) read(body, "$.accessToken")).isNotBlank();
        assertThat((String) read(body, "$.refreshToken")).isNotBlank();
        assertThat((String) read(body, "$.user.role")).isEqualTo("ADMIN");
        assertThat((String) read(body, "$.user.orgId")).isEqualTo(org.id().toString());

        String me = get(org.token(), "/api/auth/me").ok();
        assertThat((String) read(me, "$.username")).isEqualTo(org.adminUsername());
    }

    @Test
    void protectedEndpointsRequireAToken() throws Exception {
        get(null, "/api/properties").andReturnBodyExpecting(401);
        get("not-a-valid-token", "/api/properties").andReturnBodyExpecting(401);
    }

    @Test
    void accountLocksAfterFiveFailedAttempts() throws Exception {
        Org org = newOrg();
        for (int i = 0; i < 4; i++) {
            String body = post(null, "/api/auth/login", """
                    {"identifier":"%s","password":"wrong1234"}""".formatted(org.adminUsername()))
                    .andReturnBodyExpecting(401);
            assertThat((String) read(body, "$.detail")).contains("attempts left");
        }
        post(null, "/api/auth/login", """
                {"identifier":"%s","password":"wrong1234"}""".formatted(org.adminUsername())).andReturnBodyExpecting(401);
        // Even the right password is refused once locked.
        String body = post(null, "/api/auth/login", """
                {"identifier":"%s","password":"%s","deviceKey":"test-device"}""".formatted(org.adminUsername(), PASSWORD))
                .andReturnBodyExpecting(401);
        assertThat((String) read(body, "$.detail")).contains("locked");
    }

    @Test
    void refreshTokensRotateAndLogoutRevokes() throws Exception {
        Org org = newOrg();
        String login = post(null, "/api/auth/login", """
                {"identifier":"%s","password":"%s","deviceKey":"test-device"}""".formatted(org.adminUsername(), PASSWORD)).ok();
        String refresh1 = read(login, "$.refreshToken");

        String refreshed = post(null, "/api/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(refresh1)).ok();
        String refresh2 = read(refreshed, "$.refreshToken");
        assertThat(refresh2).isNotEqualTo(refresh1);
        get(read(refreshed, "$.accessToken"), "/api/auth/me").ok();

        // A used refresh token cannot be replayed.
        post(null, "/api/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(refresh1)).andReturnBodyExpecting(401);

        post(null, "/api/auth/logout", "{\"refreshToken\":\"%s\"}".formatted(refresh2)).andReturnBodyExpecting(204);
        post(null, "/api/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(refresh2)).andReturnBodyExpecting(401);
    }

    @Test
    void changePasswordValidatesAndWorks() throws Exception {
        Org org = newOrg();
        post(org.token(), "/api/auth/change-password", """
                {"currentPassword":"wrong","newPassword":"NewSecret456"}""").andReturnBodyExpecting(400);
        post(org.token(), "/api/auth/change-password", """
                {"currentPassword":"%s","newPassword":"short"}""".formatted(PASSWORD)).andReturnBodyExpecting(400);
        post(org.token(), "/api/auth/change-password", """
                {"currentPassword":"%s","newPassword":"NewSecret456"}""".formatted(PASSWORD)).andReturnBodyExpecting(204);
        login(org.adminUsername(), "NewSecret456");
    }

    @Test
    void signupRejectsDuplicateUsernameAcrossOrganisations() throws Exception {
        Org org = newOrg();
        post(null, "/api/auth/signup", """
                {"organizationName":"Other","name":"X","username":"%s","password":"%s","mobile":"01799999999"}"""
                .formatted(org.adminUsername(), PASSWORD)).andReturnBodyExpecting(409);
    }

    @Test
    void forgotPasswordNeverRevealsAccounts() throws Exception {
        post(null, "/api/auth/forgot-password", "{\"identifier\":\"nobody-here\"}").andReturnBodyExpecting(202);
        Org org = newOrg();
        post(null, "/api/auth/forgot-password", "{\"identifier\":\"%s\"}".formatted(org.adminUsername()))
                .andReturnBodyExpecting(202);
        String inbox = get(org.token(), "/api/notifications").ok();
        assertThat((String) read(inbox, "$.items[0].title")).isEqualTo("Password reset requested");
    }
}
