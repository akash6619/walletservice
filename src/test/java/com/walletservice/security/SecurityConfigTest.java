package com.walletservice.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigTest {

    private static final String SECRET = "01234567890123456789012345678901";
    private static final String ISSUER = "wallet-service-demo";
    private static final String AUDIENCE = "wallet-service";

    private final SecurityConfig config = new SecurityConfig();
    private final JwtDecoder decoder = config.jwtDecoder(SECRET, ISSUER, AUDIENCE);

    @Test
    void acceptsValidHs256Token() {
        String subject = UUID.randomUUID().toString();

        assertThat(decoder.decode(token(subject, AUDIENCE, Instant.now().plusSeconds(60))).getSubject())
                .isEqualTo(subject);
    }

    @Test
    void rejectsWrongAudience() {
        assertThatThrownBy(() -> decoder.decode(token(
                UUID.randomUUID().toString(),
                "another-service",
                Instant.now().plusSeconds(60)
        ))).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsNonUuidSubject() {
        assertThatThrownBy(() -> decoder.decode(token(
                "not-a-uuid",
                AUDIENCE,
                Instant.now().plusSeconds(60)
        ))).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() {
        assertThatThrownBy(() -> decoder.decode(token(
                UUID.randomUUID().toString(),
                AUDIENCE,
                Instant.now().minusSeconds(60)
        ))).isInstanceOf(JwtException.class);
    }

    private static String token(String subject, String audience, Instant expiresAt) {
        ImmutableSecret<SecurityContext> key = new ImmutableSecret<>(SECRET.getBytes(StandardCharsets.UTF_8));
        JwtEncoder encoder = new NimbusJwtEncoder(key);
        Instant issuedAt = expiresAt.isBefore(Instant.now())
                ? expiresAt.minusSeconds(60)
                : Instant.now().minusSeconds(5);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(List.of(audience))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
