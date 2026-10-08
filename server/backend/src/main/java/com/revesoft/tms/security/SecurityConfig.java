package com.revesoft.tms.security;

import com.revesoft.tms.auth.AccountStatusFilter;
import com.revesoft.tms.license.LicenseFilter;
import com.revesoft.tms.license.LicenseService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC = {
            "/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/api/auth/signup", "/api/auth/forgot-password",
            "/api/app/releases/*",
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/health", "/error"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, LicenseService licenses, JdbcTemplate jdbc)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC).permitAll()
                        // The web console's static files (WebConsoleConfig); its data comes from /api.
                        .requestMatchers(HttpMethod.GET, "/", "/console", "/console/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                // Not beans on purpose: they must only run inside the security chain. A blocked user
                // is told so first, whatever the state of the licence.
                .addFilterAfter(new CallerLoggingFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new AccountStatusFilter(jdbc), CallerLoggingFilter.class)
                .addFilterAfter(new LicenseFilter(licenses), AccountStatusFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(TokenService tokens) {
        return tokens.decoder();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName(TokenService.CLAIM_ROLE);
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }
}
