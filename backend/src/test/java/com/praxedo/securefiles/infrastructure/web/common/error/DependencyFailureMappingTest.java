package com.praxedo.securefiles.infrastructure.web.common.error;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A database that cannot be reached is a dependency failure — 503 and « retry » — however Spring reports it. */
class DependencyFailureMappingTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new ApiExceptionHandler(new SimpleMeterRegistry()))
            .build();

    @Test
    @DisplayName("no connection to begin a transaction with: 503, not 500")
    void a_transaction_that_cannot_begin_is_a_dependency_failure() throws Exception {
        mvc.perform(get("/no-transaction"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("a data access failure: 503, as before")
    void a_data_access_failure_is_a_dependency_failure() throws Exception {
        mvc.perform(get("/data-access"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @RestController
    static class FailingController {

        @GetMapping("/no-transaction")
        String noTransaction() {
            throw new CannotCreateTransactionException("Could not open JDBC Connection for transaction");
        }

        @GetMapping("/data-access")
        String dataAccess() {
            throw new DataAccessResourceFailureException("Connection refused");
        }
    }
}
