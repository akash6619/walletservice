package com.walletservice.controller;

import com.walletservice.exception.ApiExceptionHandler;
import com.walletservice.exception.TransferNotFoundException;
import com.walletservice.model.AccountResult;
import com.walletservice.model.Transfer;
import com.walletservice.model.TransferResult;
import com.walletservice.model.TransferStatus;
import com.walletservice.security.SecurityConfig;
import com.walletservice.security.SecurityErrorWriter;
import com.walletservice.service.AccountService;
import com.walletservice.service.TransferQueryService;
import com.walletservice.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {AccountController.class, TransferController.class},
        properties = {
                "wallet.jwt.secret=01234567890123456789012345678901",
                "wallet.jwt.issuer=wallet-service-demo",
                "wallet.jwt.audience=wallet-service"
        }
)
@Import({SecurityConfig.class, SecurityErrorWriter.class, ApiExceptionHandler.class})
class WalletApiTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RECIPIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;
    @MockitoBean
    private TransferService transferService;
    @MockitoBean
    private TransferQueryService transferQueryService;

    @Test
    void rejectsMissingBearerToken() throws Exception {
        mockMvc.perform(get("/accounts/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void createsOrGetsAuthenticatedUsersAccount() throws Exception {
        when(accountService.getOrCreate(USER_ID)).thenReturn(new AccountResult(100_000L, true));

        mockMvc.perform(post("/accounts").with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100_000L));
    }

    @Test
    void appliesTransferUsingAuthenticatedSubject() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(transferService.transfer(USER_ID, RECIPIENT_ID, 1_500L, "payment-1"))
                .thenReturn(TransferResult.applied(transferId, 98_500L, false, false));

        mockMvc.perform(post("/transfers")
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "to_user": "%s",
                                  "amount_paise": 1500,
                                  "idempotency_key": "payment-1"
                                }
                                """.formatted(RECIPIENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfer_id").value(transferId.toString()))
                .andExpect(jsonPath("$.new_balance").value(98_500L));
    }

    @Test
    void returnsPersistedInsufficientFundsAs422() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(transferService.transfer(USER_ID, RECIPIENT_ID, 200_000L, "payment-2"))
                .thenReturn(TransferResult.insufficientFunds(transferId, false, false));

        mockMvc.perform(post("/transfers")
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "to_user": "%s",
                                  "amount_paise": 200000,
                                  "idempotency_key": "payment-2"
                                }
                                """.formatted(RECIPIENT_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("insufficient_funds"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void rejectsUnknownJsonFields() throws Exception {
        mockMvc.perform(post("/transfers")
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "to_user": "%s",
                                  "amount_paise": 100,
                                  "idempotency_key": "payment-3",
                                  "from_user": "%s"
                                }
                                """.formatted(RECIPIENT_ID, RECIPIENT_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void returnsOnlyPublicTransferDetails() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(transferQueryService.getVisibleTransfer(transferId, USER_ID)).thenReturn(new Transfer(
                transferId,
                USER_ID,
                RECIPIENT_ID,
                250L,
                "secret-key",
                TransferStatus.APPLIED,
                99_750L,
                Instant.parse("2026-09-12T00:00:00Z")
        ));

        mockMvc.perform(get("/transfers/{id}", transferId)
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfer_id").value(transferId.toString()))
                .andExpect(jsonPath("$.from_user").value(USER_ID.toString()))
                .andExpect(jsonPath("$.to_user").value(RECIPIENT_ID.toString()))
                .andExpect(jsonPath("$.amount_paise").value(250L))
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.idempotency_key").doesNotExist())
                .andExpect(jsonPath("$.sender_balance_after").doesNotExist());
    }

    @Test
    void hidesTransferExistenceFromNonParticipants() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(transferQueryService.getVisibleTransfer(transferId, USER_ID))
                .thenThrow(new TransferNotFoundException());

        mockMvc.perform(get("/transfers/{id}", transferId)
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void rejectsMalformedTransferIdAsBadRequest() throws Exception {
        mockMvc.perform(get("/transfers/not-a-uuid")
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void returnsNotFoundForUnknownRoute() throws Exception {
        mockMvc.perform(get("/unknown")
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void returnsMethodNotAllowedForUnsupportedMethod() throws Exception {
        mockMvc.perform(put("/accounts")
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("method_not_allowed"));
    }
}
