package uk.gegc.quizmaker.features.billing.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.billing.api.dto.BalanceDto;
import uk.gegc.quizmaker.features.billing.api.dto.TransactionDto;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.CheckoutReadService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionSource;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BillingCheckoutController.class)
@DisplayName("Billing Controller Security Tests")
class BillingControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BillingService billingService;

    @MockitoBean
    private CheckoutReadService checkoutReadService;

    @MockitoBean
    private StripeService stripeService;

    @MockitoBean
    private EstimationService estimationService;

    @MockitoBean
    private RateLimitService rateLimitService;

    private UUID userId;
    private BalanceDto testBalance;
    private TransactionDto testTransaction;

    @BeforeEach
    void setUp() {
        userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        
        testBalance = new BalanceDto(
                userId,
                1000L,
                200L,
                LocalDateTime.now()
        );
        
        testTransaction = new TransactionDto(
                UUID.randomUUID(),
                userId,
                TokenTransactionType.PURCHASE,
                TokenTransactionSource.STRIPE,
                500L,
                "ref_123",
                "idempotency_123",
                1500L,
                0L,
                "{\"packId\": \"basic_pack\"}",
                LocalDateTime.now()
        );
    }

    @Test
    @DisplayName("GET /balance should return 401 when user is not authenticated")
    void getBalance_WhenNotAuthenticated_ShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/billing/balance"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000")
    @DisplayName("GET /balance should return 200 when user is authenticated")
    void getBalance_WhenAuthenticated_ShouldReturn200() throws Exception {
        when(billingService.getBalance(any(UUID.class))).thenReturn(testBalance);
        
        mockMvc.perform(get("/api/v1/billing/balance"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.availableTokens").value(1000))
                .andExpect(jsonPath("$.reservedTokens").value(200))
                .andExpect(header().string("Cache-Control", "private, max-age=30"));
    }


    @Test
    @DisplayName("GET /transactions should return 401 when user is not authenticated")
    void getTransactions_WhenNotAuthenticated_ShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/billing/transactions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000")
    @DisplayName("GET /transactions should return 200 when user is authenticated")
    void getTransactions_WhenAuthenticated_ShouldReturn200() throws Exception {
        Page<TransactionDto> transactionPage = new PageImpl<>(
                List.of(testTransaction),
                PageRequest.of(0, 20),
                1
        );
        
        when(billingService.listTransactions(
                any(UUID.class),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(transactionPage);
        
        mockMvc.perform(get("/api/v1/billing/transactions"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content[0].userId").value(userId.toString()))
                .andExpect(jsonPath("$.content[0].type").value("PURCHASE"))
                .andExpect(jsonPath("$.content[0].source").value("STRIPE"))
                .andExpect(jsonPath("$.content[0].amountTokens").value(500))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(header().string("Cache-Control", "private, max-age=60"));
    }


    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000")
    @DisplayName("GET /transactions should support filtering by type")
    void getTransactions_WithTypeFilter_ShouldFilterCorrectly() throws Exception {
        Page<TransactionDto> transactionPage = new PageImpl<>(
                List.of(testTransaction),
                PageRequest.of(0, 20),
                1
        );
        
        when(billingService.listTransactions(
                eq(userId),
                any(),
                eq(TokenTransactionType.PURCHASE),
                any(),
                any(),
                any()
        )).thenReturn(transactionPage);
        
        mockMvc.perform(get("/api/v1/billing/transactions")
                        .param("type", "PURCHASE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("PURCHASE"));
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000")
    @DisplayName("GET /transactions should support filtering by source")
    void getTransactions_WithSourceFilter_ShouldFilterCorrectly() throws Exception {
        Page<TransactionDto> transactionPage = new PageImpl<>(
                List.of(testTransaction),
                PageRequest.of(0, 20),
                1
        );
        
        when(billingService.listTransactions(
                eq(userId),
                any(),
                any(),
                eq(TokenTransactionSource.STRIPE),
                any(),
                any()
        )).thenReturn(transactionPage);
        
        mockMvc.perform(get("/api/v1/billing/transactions")
                        .param("source", "STRIPE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].source").value("STRIPE"));
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000")
    @DisplayName("GET /transactions should support pagination")
    void getTransactions_WithPagination_ShouldPaginateCorrectly() throws Exception {
        Page<TransactionDto> transactionPage = new PageImpl<>(
                List.of(testTransaction),
                PageRequest.of(1, 10),
                25
        );
        
        when(billingService.listTransactions(
                eq(userId),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(transactionPage);
        
        mockMvc.perform(get("/api/v1/billing/transactions")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000")
    @DisplayName("GET /transactions should support date range filtering")
    void getTransactions_WithDateRangeFilter_ShouldFilterCorrectly() throws Exception {
        Page<TransactionDto> transactionPage = new PageImpl<>(
                List.of(testTransaction),
                PageRequest.of(0, 20),
                1
        );
        
        LocalDateTime dateFrom = LocalDateTime.now().minusDays(30);
        LocalDateTime dateTo = LocalDateTime.now();
        
        when(billingService.listTransactions(
                eq(userId),
                any(),
                any(),
                any(),
                eq(dateFrom),
                eq(dateTo)
        )).thenReturn(transactionPage);
        
        mockMvc.perform(get("/api/v1/billing/transactions")
                        .param("dateFrom", dateFrom.toString())
                        .param("dateTo", dateTo.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].userId").value(userId.toString()));
    }
}
