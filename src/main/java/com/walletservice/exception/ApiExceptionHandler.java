package com.walletservice.exception;

import com.walletservice.dto.ApiError;
import com.walletservice.observability.BusinessObservability;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.Set;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Set<String> RETRYABLE_SQL_STATES = Set.of("40P01", "55P03", "57014");
    private final BusinessObservability observability;

    public ApiExceptionHandler(BusinessObservability observability) {
        this.observability = observability;
    }

    @ExceptionHandler(InvalidTransferException.class)
    ResponseEntity<ApiError> invalidTransfer(InvalidTransferException exception) {
        return error(HttpStatus.BAD_REQUEST, "invalid_transfer", exception.getMessage(), false);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", "The request body is invalid", false);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidPath(MethodArgumentTypeMismatchException exception) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", "The path parameter is invalid", false);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiError> idempotencyConflict(IdempotencyConflictException exception) {
        observability.idempotencyConflict();
        return error(HttpStatus.CONFLICT, "idempotency_conflict", exception.getMessage(), false);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ResponseEntity<ApiError> insufficientFunds(InsufficientFundsException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient_funds", exception.getMessage(), false);
    }

    @ExceptionHandler(UnregisteredCallerException.class)
    ResponseEntity<ApiError> unregisteredCaller(UnregisteredCallerException exception) {
        return error(HttpStatus.FORBIDDEN, "unregistered_caller", exception.getMessage(), false);
    }

    @ExceptionHandler({RecipientNotFoundException.class, AccountNotFoundException.class,
            TransferNotFoundException.class})
    ResponseEntity<ApiError> notFound(RuntimeException exception) {
        return error(HttpStatus.NOT_FOUND, "not_found", exception.getMessage(), false);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> routeNotFound(NoResourceFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "not_found", "The requested resource was not found", false);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "The HTTP method is not supported", false);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> databaseFailure(DataAccessException exception) {
        if (isRetryable(exception)) {
            observability.retryableDatabaseFailure();
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "1");
            return new ResponseEntity<>(
                    body("database_temporarily_unavailable", "Please retry with the same idempotency key", true),
                    headers,
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred", false);
    }

    @ExceptionHandler(InvariantViolationException.class)
    ResponseEntity<ApiError> invariantViolation(InvariantViolationException exception) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred", false);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred", false);
    }

    private static boolean isRetryable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if (sqlState != null && (sqlState.startsWith("08") || RETRYABLE_SQL_STATES.contains(sqlState))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static ResponseEntity<ApiError> error(
            HttpStatus status,
            String code,
            String message,
            boolean retryable
    ) {
        return ResponseEntity.status(status).body(body(code, message, retryable));
    }

    private static ApiError body(String code, String message, boolean retryable) {
        return new ApiError(code, message, retryable, MDC.get("correlation_id"));
    }
}
