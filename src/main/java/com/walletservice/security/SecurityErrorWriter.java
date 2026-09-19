package com.walletservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletservice.dto.ApiError;
import com.walletservice.observability.BusinessObservability;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Writes authentication/authorization failures using the same JSON contract as controller errors. */
@Component
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;
    private final BusinessObservability observability;

    /** Creates the writer with JSON serialization and telemetry dependencies. */
    public SecurityErrorWriter(ObjectMapper objectMapper, BusinessObservability observability) {
        this.objectMapper = objectMapper;
        this.observability = observability;
    }

    /**
     * Records an authentication failure and writes a complete JSON response.
     * This is used before MVC exception handling is available in the security filter chain.
     */
    public void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        observability.authFailed();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                new ApiError(code, message, false, MDC.get("correlation_id"))
        );
    }
}
