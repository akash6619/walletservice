package com.walletservice.exception;

import com.walletservice.dto.ApiError;
import com.walletservice.observability.BusinessObservability;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.Set;

/**
 * Central translation from application and infrastructure exceptions to the stable API error
 * envelope. Internal diagnostics are deliberately not returned to clients.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Set<String> RETRYABLE_SQL_STATES = Set.of("40P01", "55P03", "57014");
    private final BusinessObservability observability;

    /** Creates the handler with its business telemetry collaborator. */
    public ApiExceptionHandler(BusinessObservability observability) {
        this.observability = observability;
    }

    /** Maps domain validation failures to HTTP 400. */
    @ExceptionHandler(InvalidTransferException.class)
    ResponseEntity<ApiError> invalidTransfer(InvalidTransferException exception) {
        return error(HttpStatus.BAD_REQUEST, "invalid_transfer", exception.getMessage(), false);
    }

    /** Maps malformed or bean-validation-failing JSON bodies to HTTP 400. */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", "The request body is invalid", false);
    }

    /** Maps path conversion failures, such as a non-UUID transfer ID, to HTTP 400. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidPath(MethodArgumentTypeMismatchException exception) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", "The path parameter is invalid", false);
    }

    /** Records and maps conflicting idempotent retries to HTTP 409. */
    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiError> idempotencyConflict(IdempotencyConflictException exception) {
        observability.idempotencyConflict();
        return error(HttpStatus.CONFLICT, "idempotency_conflict", exception.getMessage(), false);
    }

    /** Maps a durably rejected transfer to HTTP 422. */
    @ExceptionHandler(InsufficientFundsException.class)
    ResponseEntity<ApiError> insufficientFunds(InsufficientFundsException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient_funds", exception.getMessage(), false);
    }

    /** Maps an authenticated but unregistered/inactive caller to HTTP 403. */
    @ExceptionHandler(UnregisteredCallerException.class)
    ResponseEntity<ApiError> unregisteredCaller(UnregisteredCallerException exception) {
        return error(HttpStatus.FORBIDDEN, "unregistered_caller", exception.getMessage(), false);
    }

    /** Maps absent or caller-hidden domain resources to HTTP 404. */
    @ExceptionHandler({RecipientNotFoundException.class, AccountNotFoundException.class,
            TransferNotFoundException.class})
    ResponseEntity<ApiError> notFound(RuntimeException exception) {
        return error(HttpStatus.NOT_FOUND, "not_found", exception.getMessage(), false);
    }

    /** Maps requests that reached no controller route to HTTP 404. */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> routeNotFound(NoResourceFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "not_found", "The requested resource was not found", false);
    }

    /** Maps use of an unsupported HTTP method to HTTP 405. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "The HTTP method is not supported", false);
    }

    /**
     * Separates transient database failures from permanent/unclassified failures. Retryable
     * responses instruct clients to reuse the same idempotency key, preserving at-most-once intent.
     */
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    ResponseEntity<ApiError> databaseFailure(Exception exception) {
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

    /** Hides internal invariant details while returning HTTP 500. */
    @ExceptionHandler(InvariantViolationException.class)
    ResponseEntity<ApiError> invariantViolation(InvariantViolationException exception) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred", false);
    }

    /** Last-resort mapping that prevents implementation details escaping the service. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred", false);
    }

    /** Walks the cause chain looking for connection, transient, lock, or timeout SQL failures. */
    private static boolean isRetryable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof CannotCreateTransactionException
                    || current instanceof CannotGetJdbcConnectionException
                    || current instanceof TransientDataAccessException) {
                return true;
            }
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

    /** Builds an HTTP response around the service's standard error envelope. */
    private static ResponseEntity<ApiError> error(
            HttpStatus status,
            String code,
            String message,
            boolean retryable
    ) {
        return ResponseEntity.status(status).body(body(code, message, retryable));
    }

    /** Adds the request correlation ID from MDC to every controller-generated error. */
    private static ApiError body(String code, String message, boolean retryable) {
        return new ApiError(code, message, retryable, MDC.get("correlation_id"));
    }
}
