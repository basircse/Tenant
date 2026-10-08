package com.revesoft.tms.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/** Issues and verifies short-lived HS256 access tokens. */
@Service
public class TokenService {

    public static final String CLAIM_ORG = "org";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_NAME = "name";
    public static final String CLAIM_TENANT = "tid";
    public static final String CLAIM_DEVICE = "dev";
    private static final String ISSUER = "tms";

    private final SecretKey key;
    private final JwtEncoder encoder;
    private final Duration accessTtl;

    public TokenService(@Value("${tms.security.jwt-secret}") String secret,
                        @Value("${tms.security.access-token-minutes}") long accessMinutes) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("tms.security.jwt-secret must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.accessTtl = Duration.ofMinutes(accessMinutes);
    }

    public JwtDecoder decoder() {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    public String issueAccessToken(AuthUser user) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.userId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(accessTtl))
                .claim(CLAIM_ORG, user.orgId().toString())
                .claim(CLAIM_ROLE, user.role().name())
                .claim(CLAIM_NAME, user.name());
        if (user.tenantId() != null) {
            claims.claim(CLAIM_TENANT, user.tenantId().toString());
        }
        if (user.deviceId() != null) {
            claims.claim(CLAIM_DEVICE, user.deviceId().toString());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
