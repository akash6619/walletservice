package com.walletservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletservice.dto.ApiError;
import com.walletservice.observability.BusinessObservability;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;
    private final BusinessObservability observability;

    public SecurityErrorWriter(ObjectMapper objectMapper, BusinessObservability observability) {
        this.objectMapper = objectMapper;
        this.observability = observability;
    }

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
