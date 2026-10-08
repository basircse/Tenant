package com.revesoft.tms.license;

import com.revesoft.tms.org.Organization;
import com.revesoft.tms.org.OrganizationRepository;
import com.revesoft.tms.security.Role;
import com.revesoft.tms.security.TxRunner;
import com.revesoft.tms.user.AppUser;
import com.revesoft.tms.user.PasswordPolicy;
import com.revesoft.tms.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the vendor organisation and the first vendor user on startup when
 * {@code tms.vendor.username} and {@code tms.vendor.password} are configured and the user does not
 * exist yet. Change the password after the first login.
 */
@Component
@Order(0)
public class VendorBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VendorBootstrap.class);

    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final TxRunner tx;
    private final String username;
    private final String password;
    private final String name;

    public VendorBootstrap(OrganizationRepository orgs, UserRepository users, PasswordEncoder encoder, TxRunner tx,
                           @Value("${tms.vendor.username:}") String username,
                           @Value("${tms.vendor.password:}") String password,
                           @Value("${tms.vendor.name:Vendor}") String name) {
        this.orgs = orgs;
        this.users = users;
        this.encoder = encoder;
        this.tx = tx;
        this.username = username;
        this.password = password;
        this.name = name;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank() || password.isBlank()) {
            if (tx.asRoot(() -> orgs.findByVendorTrue().isEmpty())) {
                log.warn("No vendor account configured. Set TMS_VENDOR_USERNAME and TMS_VENDOR_PASSWORD to create one.");
            }
            return;
        }
        if (tx.asRoot(() -> users.existsByUsernameIgnoreCase(username))) {
            return;
        }
        PasswordPolicy.validate(password);
        Organization vendorOrg = tx.asRoot(() -> orgs.findByVendorTrue().stream().findFirst().orElseGet(() -> {
            Organization o = new Organization();
            o.setName(name);
            o.setVendor(true);
            o.setStatus(Organization.Status.ACTIVE);
            return orgs.save(o);
        }));
        tx.inOrg(vendorOrg.getId(), () -> {
            AppUser u = new AppUser();
            u.setName(name);
            u.setUsername(username);
            u.setRole(Role.VENDOR);
            u.setPasswordHash(encoder.encode(password));
            u.setMustChangePassword(true);
            return users.save(u);
        });
        log.info("Created vendor user '{}'", username);
    }
}
