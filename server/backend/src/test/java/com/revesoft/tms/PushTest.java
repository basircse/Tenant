package com.revesoft.tms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.revesoft.tms.notification.NotificationRepository;
import com.revesoft.tms.push.PushSender;
import com.revesoft.tms.push.PushSender.PushMessage;
import com.revesoft.tms.push.PushToken;
import com.revesoft.tms.push.PushTokenRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Push token registration and delivery of in-app notifications as pushes after commit. */
class PushTest extends ApiTestBase {

    @MockitoBean
    PushSender sender;

    @Autowired
    PushTokenRepository tokens;

    @Autowired
    NotificationRepository notifications;

    private PushToken token(String value) {
        return tx.asRoot(() -> tokens.findByToken(value).orElse(null));
    }

    private static String fcmToken() {
        return "fcm-" + UUID.randomUUID() + "-" + UUID.randomUUID();
    }

    private static UUID userOf(String accessToken) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]));
        return UUID.fromString(read(payload, "$.sub"));
    }

    @Test
    void tokensAreRegisteredMovedBetweenUsersAndRemoved() throws Exception {
        Org org = newOrg();
        String admin = org.token();
        String tenantLogin = uniq("ten");
        createTenant(admin, "Karim", tenantLogin);
        String tenant = login(tenantLogin, PASSWORD);
        String value = fcmToken();

        String view = post(admin, "/api/push/token", "{\"token\":\"%s\",\"platform\":\"android\"}".formatted(value)).ok();
        assertThat((String) read(view, "$.app")).isEqualTo("ADMIN");
        assertThat(token(value).getUserId()).isEqualTo(userOf(admin));
        assertThat(token(value).getOrgId()).isEqualTo(org.id());
        // Registering again is an update, not a duplicate.
        post(admin, "/api/push/token", "{\"token\":\"%s\",\"platform\":\"android\"}".formatted(value)).ok();

        // The same phone is now used by the tenant: the token moves to them.
        String moved = post(tenant, "/api/push/token", "{\"token\":\"%s\",\"platform\":\"ios\"}".formatted(value)).ok();
        assertThat((String) read(moved, "$.app")).isEqualTo("TENANT");
        assertThat(token(value).getUserId()).isEqualTo(userOf(tenant));
        assertThat(token(value).getPlatform()).isEqualTo("ios");

        // ...and to a user of another organisation.
        Org other = newOrg();
        post(other.token(), "/api/push/token", "{\"token\":\"%s\"}".formatted(value)).ok();
        assertThat(token(value).getOrgId()).isEqualTo(other.id());

        // Only the owner can remove it.
        delete(tenant, "/api/push/token").andReturnBodyExpecting(400);
        request(tenant, value);
        assertThat(token(value)).isNotNull();
        request(other.token(), value);
        assertThat(token(value)).isNull();

        // Signing out with the push token removes it as well.
        String second = fcmToken();
        String refresh = read(loginCall(org.adminUsername(), PASSWORD, "phone-X").ok(), "$.refreshToken");
        post(admin, "/api/push/token", "{\"token\":\"%s\"}".formatted(second)).ok();
        post(null, "/api/auth/logout", "{\"refreshToken\":\"%s\",\"pushToken\":\"%s\"}".formatted(refresh, second))
                .andReturnBodyExpecting(204);
        assertThat(token(second)).isNull();
    }

    private void request(String accessToken, String value) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/push/token")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(value)))
                .andReturn();
    }

    @Test
    void newNotificationsArePushedAfterCommit() throws Exception {
        Org org = newOrg();
        String admin = org.token();
        String value = fcmToken();
        post(admin, "/api/push/token", "{\"token\":\"%s\",\"platform\":\"android\"}".formatted(value)).ok();

        // When the sender runs, the notification must already be committed.
        CompletableFuture<Boolean> committed = new CompletableFuture<>();
        doAnswer(inv -> {
            PushMessage m = inv.getArgument(1);
            UUID id = UUID.fromString(m.data().get("notificationId"));
            committed.complete(tx.asRoot(() -> notifications.existsById(id)));
            return Set.of();
        }).when(sender).send(anyList(), any());

        createTenant(admin, "Karim", null); // notifies staff: "New tenant registered"

        verify(sender, timeout(5000)).send(argThat(list -> list.equals(List.of(value))),
                argThat(m -> m.title().equals("New tenant registered") && m.body().contains("Karim")
                        && "notification".equals(m.data().get("type"))));
        assertThat(committed.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void deadTokensAreDeletedAndBlockedOrganisationsGetNoPush() throws Exception {
        Org org = newOrg();
        String admin = org.token();
        String dead = fcmToken();
        post(admin, "/api/push/token", "{\"token\":\"%s\"}".formatted(dead)).ok();
        when(sender.send(anyList(), any())).thenReturn(Set.of(dead));

        createTenant(admin, "Karim", null);
        verify(sender, timeout(5000)).send(argThat(list -> list.contains(dead)), any());
        long deadline = System.currentTimeMillis() + 5000;
        while (token(dead) != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(token(dead)).isNull();

        // A suspended landlord gets no pushes (the in-app notification is still stored).
        Org blocked = newOrg();
        String live = fcmToken();
        post(blocked.token(), "/api/push/token", "{\"token\":\"%s\"}".formatted(live)).ok();
        post(vendorToken(), "/api/vendor/organizations/" + blocked.id() + "/suspend", "{\"reason\":\"Unpaid\"}").ok();
        verify(sender, after(1500).never()).send(argThat(list -> list.contains(live)), any());
    }
}
