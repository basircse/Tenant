package com.revesoft.tms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Blocking users: by an organisation's admin (own organisation only) and by the vendor (anyone). */
class UserBlockTest extends ApiTestBase {

    private String createUser(String adminToken, String role, String username) throws Exception {
        return read(post(adminToken, "/api/users", """
                {"name":"%s","username":"%s","role":"%s","password":"%s"}"""
                .formatted("User " + username, username, role, PASSWORD)).created(), "$.id");
    }

    private String block(String token, String url, String reason) throws Exception {
        return post(token, url + "/block", """
                {"reason":"%s"}""".formatted(reason)).ok();
    }

    private static String code(String problem) {
        return read(problem, "$.code");
    }

    @Test
    void adminBlocksAUserWhoIsSignedOutAtOnceAndCannotSignInUntilUnblocked() throws Exception {
        Org org = newOrg();
        String manager = uniq("mgr");
        String managerId = createUser(org.token(), "MANAGER", manager);
        String device = "device-" + manager;
        String session = loginCall(manager, PASSWORD, device).ok();
        String access = read(session, "$.accessToken");
        String refresh = read(session, "$.refreshToken");
        get(access, "/api/properties").ok();

        String blocked = block(org.token(), "/api/users/" + managerId, "Left the company");
        assertThat((String) read(blocked, "$.status")).isEqualTo("BLOCKED");
        assertThat((String) read(blocked, "$.blockedReason")).isEqualTo("Left the company");
        assertThat((String) read(blocked, "$.blockedBy")).isEqualTo("Owner");
        assertThat((Boolean) read(blocked, "$.blockedByVendor")).isFalse();

        // The access token stops working immediately, and the session cannot be renewed.
        String refused = get(access, "/api/properties").andReturnBodyExpecting(403);
        assertThat(code(refused)).isEqualTo("ACCOUNT_BLOCKED");
        assertThat((String) read(refused, "$.detail")).contains("your administrator", "Left the company");
        post(null, "/api/auth/refresh", """
                {"refreshToken":"%s"}""".formatted(refresh)).andReturnBodyExpecting(401);

        // Signing in tells them why, but only with the right password; wrong guesses don't lock the account.
        assertThat(code(loginCall(manager, PASSWORD, device).andReturnBodyExpecting(403))).isEqualTo("ACCOUNT_BLOCKED");
        for (int i = 0; i < 6; i++) {
            String wrong = loginCall(manager, "Wrong1234", device).andReturnBodyExpecting(401);
            assertThat((String) read(wrong, "$.detail")).doesNotContain("Left the company");
        }
        assertThat((String) read(get(org.token(), "/api/users/" + managerId).ok(), "$.status")).isEqualTo("BLOCKED");

        // Blocking is separate from activate / deactivate.
        post(org.token(), "/api/users/" + managerId + "/status", """
                {"status":"ACTIVE"}""").andReturnBodyExpecting(400);

        String unblocked = post(org.token(), "/api/users/" + managerId + "/unblock", null).ok();
        assertThat((String) read(unblocked, "$.status")).isEqualTo("ACTIVE");
        loginCall(manager, PASSWORD, device).ok();

        List<String> actions = read(get(org.token(), "/api/audit?entityType=User").ok(), "$[*].action");
        assertThat(actions).contains("Blocked User", "Unblocked User");
    }

    @Test
    void adminsOnlyReachTheirOwnOrganisation() throws Exception {
        Org a = newOrg();
        Org b = newOrg();
        String userOfA = createUser(a.token(), "MANAGER", uniq("mgr"));

        // Another landlord cannot see, block or unblock it.
        block(a.token(), "/api/users/" + userOfA, "Testing");
        post(b.token(), "/api/users/" + userOfA + "/block", """
                {"reason":"Not mine"}""").andReturnBodyExpecting(404);
        post(b.token(), "/api/users/" + userOfA + "/unblock", null).andReturnBodyExpecting(404);
        List<String> visibleToB = read(get(b.token(), "/api/users").ok(), "$[*].id");
        assertThat(visibleToB).doesNotContain(userOfA);

        // Managers, tenants and landlords cannot use the vendor's user list.
        String manager = uniq("mgr");
        createUser(b.token(), "MANAGER", manager);
        String managerToken = login(manager, PASSWORD);
        post(managerToken, "/api/users/" + userOfA + "/block", """
                {"reason":"x"}""").andReturnBodyExpecting(403);
        get(a.token(), "/api/vendor/users").andReturnBodyExpecting(403);

        // Admins cannot block themselves, and a reason is required.
        String me = read(get(a.token(), "/api/auth/me").ok(), "$.userId");
        post(a.token(), "/api/users/" + me + "/block", """
                {"reason":"oops"}""").andReturnBodyExpecting(400);
        String other = createUser(a.token(), "ADMIN", uniq("adm"));
        post(a.token(), "/api/users/" + other + "/block", """
                {"reason":" "}""").andReturnBodyExpecting(400);
    }

    @Test
    void theVendorCanBlockAnyoneAndOnlyTheVendorCanLiftIt() throws Exception {
        Org org = newOrg();
        String vendor = vendorToken();
        String secondAdmin = uniq("adm");
        String secondAdminId = createUser(org.token(), "ADMIN", secondAdmin);
        String ownerId = read(get(org.token(), "/api/auth/me").ok(), "$.userId");

        // The vendor sees every organisation's users and can filter by organisation.
        String users = get(vendor, "/api/vendor/users?org=" + org.id()).ok();
        assertThat((List<String>) read(users, "$[*].user.id")).contains(ownerId, secondAdminId);
        assertThat((List<String>) read(users, "$[*].orgId")).containsOnly(org.id().toString());

        String result = block(vendor, "/api/vendor/users/" + ownerId, "Unpaid invoice");
        assertThat((String) read(result, "$.user.status")).isEqualTo("BLOCKED");
        assertThat((Boolean) read(result, "$.user.blockedByVendor")).isTrue();
        assertThat((String) read(result, "$.orgName")).startsWith("Estate ");

        // The owner is out at once, and is told the service provider did it.
        String refused = get(org.token(), "/api/properties").andReturnBodyExpecting(403);
        assertThat((String) read(refused, "$.detail")).contains("service provider", "Unpaid invoice");

        // The organisation's other admin can neither lift nor overwrite the vendor's block.
        String other = login(secondAdmin, PASSWORD);
        assertThat((String) read(post(other, "/api/users/" + ownerId + "/unblock", null).andReturnBodyExpecting(403),
                "$.detail")).contains("service provider");
        post(other, "/api/users/" + ownerId + "/block", """
                {"reason":"mine now"}""").andReturnBodyExpecting(403);

        // The vendor's action is in the organisation's audit log.
        List<String> details = read(get(other, "/api/audit?entityType=User").ok(), "$[*].details");
        assertThat(details).anyMatch(d -> d != null && d.contains("By the service provider"));
    }

    @Test
    void theVendorLiftsBlocks() throws Exception {
        Org org = newOrg();
        String vendor = vendorToken();
        String manager = uniq("mgr");
        String managerId = createUser(org.token(), "MANAGER", manager);
        block(org.token(), "/api/users/" + managerId, "Admin's block");

        String blockedOnly = get(vendor, "/api/vendor/users?status=BLOCKED&q=" + manager).ok();
        assertThat((List<String>) read(blockedOnly, "$[*].user.id")).containsExactly(managerId);

        assertThat((String) read(post(vendor, "/api/vendor/users/" + managerId + "/unblock", null).ok(),
                "$.user.status")).isEqualTo("ACTIVE");
        login(manager, PASSWORD);
        post(vendor, "/api/vendor/users/" + managerId + "/unblock", null).andReturnBodyExpecting(400);
    }
}
