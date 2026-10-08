package com.revesoft.tms.security;

import com.revesoft.tms.common.BusinessException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** Static access to the authenticated caller. */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** The caller, or null when unauthenticated. */
    public static AuthUser orNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        if (auth.getPrincipal() instanceof AuthUser u) {
            return u;
        }
        if (auth.getPrincipal() instanceof Jwt jwt) {
            return fromJwt(jwt);
        }
        return null;
    }

    public static AuthUser get() {
        AuthUser u = orNull();
        if (u == null) {
            throw BusinessException.forbidden("Not authenticated");
        }
        return u;
    }

    static AuthUser fromJwt(Jwt jwt) {
        String tenant = jwt.getClaimAsString(TokenService.CLAIM_TENANT);
        String device = jwt.getClaimAsString(TokenService.CLAIM_DEVICE);
        return new AuthUser(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString(TokenService.CLAIM_ORG)),
                Role.valueOf(jwt.getClaimAsString(TokenService.CLAIM_ROLE)),
                jwt.getClaimAsString(TokenService.CLAIM_NAME),
                tenant == null ? null : UUID.fromString(tenant),
                device == null ? null : UUID.fromString(device));
    }
}
