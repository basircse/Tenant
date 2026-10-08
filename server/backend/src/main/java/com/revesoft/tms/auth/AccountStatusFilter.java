package com.revesoft.tms.auth;

import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.license.LicenseCodes;
import com.revesoft.tms.security.AuthUser;
import com.revesoft.tms.security.CurrentUser;
import com.revesoft.tms.user.AppUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Re-checks the caller's account on every authenticated request, so blocking, deactivating or
 * locking a user takes effect at once instead of when their access token expires. Blocked users
 * get 403 {@code ACCOUNT_BLOCKED} with the reason; inactive or locked ones 401
 * {@code ACCOUNT_INACTIVE}. Signing out stays possible.
 */
public class AccountStatusFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccountStatusFilter.class);

    private final JdbcTemplate jdbc;

    public AccountStatusFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || path.equals("/api/auth/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AuthUser user = CurrentUser.orNull();
        if (user != null && user.userId() != null) {
            // Plain SQL: one primary-key lookup, independent of the organisation filter.
            List<AppUser> rows = jdbc.query(
                    "SELECT status, blocked_reason, blocked_by_vendor FROM users WHERE id = ?", (rs, n) -> {
                        AppUser u = new AppUser();
                        u.setStatus(AppUser.Status.valueOf(rs.getString("status")));
                        u.setBlockedReason(rs.getString("blocked_reason"));
                        u.setBlockedByVendor(rs.getBoolean("blocked_by_vendor"));
                        return u;
                    }, user.userId().toString());
            BusinessException refusal = null;
            if (rows.isEmpty()) {
                refusal = new BusinessException(HttpStatus.UNAUTHORIZED, LicenseCodes.ACCOUNT_INACTIVE,
                        "This account no longer exists.");
            } else if (rows.getFirst().getStatus() == AppUser.Status.BLOCKED) {
                refusal = AuthService.blocked(rows.getFirst());
            } else if (rows.getFirst().getStatus() != AppUser.Status.ACTIVE) {
                refusal = new BusinessException(HttpStatus.UNAUTHORIZED, LicenseCodes.ACCOUNT_INACTIVE,
                        "Your account is not active. Contact the administrator.");
            }
            if (refusal != null) {
                log.debug("Refused {} {} for user {}: {}", request.getMethod(), request.getRequestURI(), user.userId(),
                        refusal.getCode());
                response.setStatus(refusal.getStatus().value());
                response.setContentType("application/problem+json");
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write("{\"type\":\"about:blank\",\"title\":\""
                        + refusal.getStatus().getReasonPhrase() + "\",\"status\":" + refusal.getStatus().value()
                        + ",\"code\":\"" + refusal.getCode() + "\",\"detail\":\"" + json(refusal.getMessage())
                        + "\",\"instance\":\"" + json(request.getRequestURI()) + "\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static String json(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
