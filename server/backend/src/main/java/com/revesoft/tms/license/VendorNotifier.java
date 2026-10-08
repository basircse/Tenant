package com.revesoft.tms.license;

import com.revesoft.tms.notification.NotificationService;
import com.revesoft.tms.org.Organization;
import com.revesoft.tms.org.OrganizationRepository;
import com.revesoft.tms.security.Role;
import com.revesoft.tms.security.TxRunner;
import com.revesoft.tms.user.AppUser;
import com.revesoft.tms.user.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Sends in-app notifications to the vendor's users (they live in the vendor organisation). */
@Component
public class VendorNotifier {

    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final NotificationService notifications;
    private final TxRunner tx;

    public VendorNotifier(OrganizationRepository orgs, UserRepository users, NotificationService notifications,
                          TxRunner tx) {
        this.orgs = orgs;
        this.users = users;
        this.notifications = notifications;
        this.tx = tx;
    }

    public void notifyVendors(String title, String body, String dedupKey) {
        List<UUID> vendorOrgs = tx.asRoot(() -> orgs.findByVendorTrue().stream().map(Organization::getId).toList());
        for (UUID orgId : vendorOrgs) {
            tx.inOrg(orgId, () -> {
                for (AppUser u : users.findByRoleInAndStatus(List.of(Role.VENDOR), AppUser.Status.ACTIVE)) {
                    notifications.notifyUser(u.getId(), title, body, dedupKey);
                }
                return null;
            });
        }
    }
}
