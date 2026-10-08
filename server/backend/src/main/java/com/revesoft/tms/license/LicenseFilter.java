package com.revesoft.tms.license;

import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.security.AuthUser;
import com.revesoft.tms.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the licence to every authenticated API call (staff and tenants alike). Runs after JWT
 * authentication. Sign-in, the licence status endpoint and the vendor console stay reachable so
 * the apps can explain what is wrong.
 */
public class LicenseFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LicenseFilter.class);

    private final LicenseService licenses;

    public LicenseFilter(LicenseService licenses) {
        this.licenses = licenses;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/")
                || path.startsWith("/api/auth/")
                || path.startsWith("/api/app/")
                || path.equals("/api/license") || path.startsWith("/api/license/")
                || path.startsWith("/api/vendor/")
                // Push registration stays possible so devices are known once access is restored.
                || path.startsWith("/api/push/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AuthUser user = CurrentUser.orNull();
        if (user != null) {
            boolean write = !("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())
                    || "OPTIONS".equals(request.getMethod()));
            try {
                licenses.checkRequest(user, write);
            } catch (BusinessException e) {
                log.debug("Refused {} {} by licence: {}", request.getMethod(), request.getRequestURI(), e.getCode());
                response.setStatus(e.getStatus().value());
                response.setContentType("application/problem+json");
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + e.getStatus().getReasonPhrase()
                        + "\",\"status\":" + e.getStatus().value() + ",\"code\":\"" + e.getCode()
                        + "\",\"detail\":\"" + json(e.getMessage()) + "\",\"instance\":\"" + json(request.getRequestURI())
                        + "\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static String json(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
