package com.revesoft.tms.security;

import com.revesoft.tms.common.RequestLoggingFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the authenticated caller into the logging context ({@code user} = "username-or-name/ROLE",
 * {@code org} = organisation id), so every log line shows who it was for. Runs inside the security
 * chain right after the access token is read; {@link RequestLoggingFilter} clears the values.
 */
public class CallerLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AuthUser user = CurrentUser.orNull();
        if (user != null) {
            MDC.put(RequestLoggingFilter.USER, user.name() + "/" + (user.role() == null ? "?" : user.role().name()));
            if (user.orgId() != null) {
                MDC.put(RequestLoggingFilter.ORG, user.orgId().toString());
            }
        }
        chain.doFilter(request, response);
    }
}
