package com.walletservice.security;

import com.walletservice.exception.UnregisteredCallerException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/** Extracts the canonical application user ID from a JWT validated by Spring Security. */
public final class AuthenticatedUser {

    /** Prevents instantiation of this stateless security utility. */
    private AuthenticatedUser() {
    }

    /**
     * Parses the JWT subject as a UUID.
     *
     * @throws UnregisteredCallerException if a malformed token somehow reaches the application
     */
    public static UUID id(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new UnregisteredCallerException();
        }
    }
}
