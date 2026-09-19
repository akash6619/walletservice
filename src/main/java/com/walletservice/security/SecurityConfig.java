package com.walletservice.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Configures stateless bearer-token authentication and strict JWT claim validation. */
@Configuration
public class SecurityConfig {

    /**
     * Builds the HTTP security policy: operational probes are public, internal health paths are
     * denied, and every business endpoint requires a valid bearer token.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityErrorWriter errorWriter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/healthz", "/readyz", "/metrics").permitAll()
                        .requestMatchers("/internal-health", "/internal-health/**").denyAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, exception) -> errorWriter.write(
                                response,
                                HttpStatus.UNAUTHORIZED.value(),
                                "unauthorized",
                                "A valid bearer token is required"
                        ))
                )
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(
                        (request, response, exception) -> errorWriter.write(
                                response,
                                HttpStatus.FORBIDDEN.value(),
                                "forbidden",
                                "Access is denied"
                        )
                ))
                .build();
    }

    /**
     * Creates an HMAC JWT decoder that validates signature, timestamps, issuer, audience, and a
     * UUID-formatted subject before authentication succeeds.
     */
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${wallet.jwt.secret}") String secret,
            @Value("${wallet.jwt.issuer}") String issuer,
            @Value("${wallet.jwt.audience}") String audience
    ) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey).build();

        OAuth2TokenValidator<Jwt> issuerAndTimestamp = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = token -> token.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
        OAuth2TokenValidator<Jwt> subjectValidator = token -> isUuid(token.getSubject())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid subject", null));

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                List.of(issuerAndTimestamp, audienceValidator, subjectValidator)
        ));
        return decoder;
    }

    /** Returns whether a claim value is a syntactically valid UUID. */
    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }
    }
}
