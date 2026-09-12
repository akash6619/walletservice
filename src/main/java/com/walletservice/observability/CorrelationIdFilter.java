package com.walletservice.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "correlation_id";
    public static final String HEADER = "X-Correlation-ID";
    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelationIdFilter.class);

    private final MeterRegistry meterRegistry;

    public CorrelationIdFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String correlationId = UUID.randomUUID().toString();
        Timer.Sample timer = Timer.start(meterRegistry);
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            String route = route(request);
            String method = request.getMethod();
            String statusClass = response.getStatus() / 100 + "xx";
            meterRegistry.counter(
                    "http_requests_total",
                    "route", route,
                    "method", method,
                    "status_class", statusClass
            ).increment();
            timer.stop(Timer.builder("http_request_duration_seconds")
                    .publishPercentileHistogram()
                    .tags("route", route, "method", method)
                    .register(meterRegistry));
            LOGGER.atInfo()
                    .addKeyValue("event", "request_completed")
                    .addKeyValue("route", route)
                    .addKeyValue("method", method)
                    .addKeyValue("status", response.getStatus())
                    .log("request completed");
            MDC.remove(MDC_KEY);
        }
    }

    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String value ? value : "UNKNOWN";
    }
}
