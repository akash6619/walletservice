package com.walletservice.exception;

import com.walletservice.observability.BusinessObservability;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ApiExceptionHandlerTest {

    @Test
    void mapsPostgresDeadlockToRetryableServiceUnavailable() {
        BusinessObservability observability = mock(BusinessObservability.class);
        ApiExceptionHandler handler = new ApiExceptionHandler(observability);
        var exception = new DataAccessResourceFailureException(
                "deadlock",
                new SQLException("deadlock detected", "40P01")
        );

        var response = handler.databaseFailure(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().retryable()).isTrue();
        assertThat(response.getBody().code()).isEqualTo("database_temporarily_unavailable");
        verify(observability).retryableDatabaseFailure();
    }
}
