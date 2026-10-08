package com.revesoft.tms.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Authentication used for server-side work (sign-up, scheduled jobs) that has no HTTP caller. */
public class SystemAuthentication extends AbstractAuthenticationToken {

    private final AuthUser user;

    public SystemAuthentication(AuthUser user) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
        this.user = user;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public AuthUser getPrincipal() {
        return user;
    }
}
