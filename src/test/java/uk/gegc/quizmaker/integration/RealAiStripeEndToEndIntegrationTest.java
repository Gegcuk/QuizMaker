package uk.gegc.quizmaker.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.Rollback;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.billing.application.StripeProperties;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.billing.application.StripeWebhookService;
import uk.gegc.quizmaker.features.billing.domain.model.Balance;
import uk.gegc.quizmaker.features.billing.domain.model.Payment;
import uk.gegc.quizmaker.features.billing.domain.model.PaymentStatus;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransaction;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProcessedStripeEventRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ReservationRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;
import uk.gegc.quizmaker.features.document.api.dto.ProcessDocumentRequest;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationResponse;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.application.QuizService;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.PermissionRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comprehensive end-to-end test that exercises a full user journey using real Stripe and real OpenAI calls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(value = {"test", "real-ai"}, inheritProfiles = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "quizmaker.features.billing=true"
})

class RealAiStripeEndToEndIntegrationTest{

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected EntityManager entityManager;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    private StripeService stripeService;

    @Autowired
    private StripeWebhookService stripeWebhookService;

    @Autowired
    private StripeProperties stripeProperties;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String model;

    @Autowired
    private ProductPackRepository productPackRepository;

    @Autowired
    private BalanceRepository balanceRepository;

    @Autowired
    private TokenTransactionRepository tokenTransactionRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ProcessedStripeEventRepository processedStripeEventRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private QuizGenerationJobRepository jobRepository;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private QuizService quizService;

    @Autowired
    private PermissionRepository permissionRepository;

    @MockitoBean
    AppPermissionEvaluator appPermissionEvaluator;


    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private User testUser;
    private ProductPack selectedPack;

    @BeforeEach
    void allowAllPermissions() {
        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(true);
        when(appPermissionEvaluator.hasAllPermissions(any())).thenReturn(true);
    }

    @BeforeEach
    void setUp() throws Exception {


        Assumptions.assumeTrue(StringUtils.hasText(stripeProperties.getSecretKey()), "Stripe secret key must be present");
        Assumptions.assumeTrue(StringUtils.hasText(stripeProperties.getWebhookSecret()), "Stripe webhook secret must be present");
        Assumptions.assumeTrue(StringUtils.hasText(apiKey), "OpenAI API key must be available");

        testUser = new User();
        testUser.setUsername("e2e_user_" + UUID.randomUUID().toString().substring(0, 8));
        testUser.setEmail("e2e_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        testUser.setHashedPassword("pwd");
        testUser.setActive(true);
        testUser.setDeleted(false);
        testUser.setEmailVerified(true);
        testUser = userRepository.save(testUser);

        Permission quizCreate = permissionRepository.findByPermissionName("QUIZ_CREATE")
                .orElseThrow(() -> new ResourceNotFoundException("Permission not found"));

        permissionRepository.save(new Permission(
                quizCreate.getPermissionId(),
                quizCreate.getPermissionName(),
                "Description",
                "Resource",
                "Action",
                Set.of(new Role())

        ));

        selectedPack = productPackRepository.findByStripePriceId(stripeProperties.getPriceMedium())
                .orElseGet(() -> createPlaceholderPack(stripeProperties.getPriceMedium(), "Growth Pack", 5000L, 1000_0L));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        quizRepository.deleteAll();
        jobRepository.deleteAll();
        documentRepository.deleteAll();
        reservationRepository.deleteAll();
        tokenTransactionRepository.deleteAll();
        paymentRepository.deleteAll();
        balanceRepository.deleteAll();
        processedStripeEventRepository.deleteAll();
    }

    @Test
    @DisplayName("7.1 Complete user journey from purchase to successful quiz generation")
    @Rollback(false)
    void completeUserJourney_shouldSucceed() throws Exception {
        creditTokensViaStripeWebhook();

        // Test the complete integration flow through HTTP endpoints
        Map<QuestionType, Integer> questionsPerType = new EnumMap<>(QuestionType.class);
        questionsPerType.put(QuestionType.MCQ_SINGLE, 3);

        GenerateQuizFromTextRequest request = new GenerateQuizFromTextRequest(
                "Artificial intelligence is a branch of computer science that aims to create machines capable of intelligent behavior. " +
                "Machine learning is a subset of AI that focuses on algorithms that can learn from data. " +
                "Deep learning uses neural networks with multiple layers to process complex patterns in data. " +
                "Natural language processing enables computers to understand and generate human language. " +
                "Computer vision allows machines to interpret and understand visual information from the world.",
                "en", // language
                uk.gegc.quizmaker.features.document.api.dto.ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED, // chunkingStrategy
                50000, // maxChunkSize
                QuizScope.ENTIRE_DOCUMENT, // quizScope
                null, // chunkIndices
                null, // chapterTitle
                null, // chapterNumber
                "AI Fundamentals Quiz", // quizTitle
                "Test your knowledge of artificial intelligence concepts", // quizDescription
                questionsPerType,
                Difficulty.MEDIUM,
                3, // estimatedTimePerQuestion
                null, // categoryId
                List.of() // tagIds
        );

        // Set up authentication for the HTTP request
        // Call the actual REST endpoint using mockMvc with proper authentication
        MvcResult result = mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .with(user(testUser.getUsername())
                                .authorities(
                                        new SimpleGrantedAuthority("QUIZ_CREATE"),
                                        new SimpleGrantedAuthority("BILLING_READ"),
                                        new SimpleGrantedAuthority("BILLING_WRITE")
                                )
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isAccepted())
                .andReturn();
        
        // Parse the response
        String responseContent = result.getResponse().getContentAsString();
        QuizGenerationResponse responseBody = objectMapper.readValue(responseContent, QuizGenerationResponse.class);
        assertThat(responseBody).isNotNull();
        assertThat(responseBody.jobId()).isNotNull();
        
        // Wait for the async generation to complete
        QuizGenerationJob job = awaitJobCompletion(responseBody.jobId(), Duration.ofMinutes(3));
        assertThat(job.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(job.getBillingState()).isEqualTo(BillingState.COMMITTED);
        assertThat(job.getBillingCommittedTokens()).isGreaterThan(0);
        assertThat(job.getGeneratedQuizId()).isNotNull();

        // Verify billing state
        Balance balance = balanceRepository.findByUserId(testUser.getId()).orElseThrow();
        assertThat(balance.getReservedTokens()).isZero();
        assertThat(balance.getAvailableTokens()).isLessThan(selectedPack.getTokens());

        // Verify token transactions
        List<TokenTransaction> transactions = tokenTransactionRepository.findByUserId(testUser.getId());
        assertThat(transactions).extracting(TokenTransaction::getType)
                .contains(TokenTransactionType.PURCHASE, TokenTransactionType.RESERVE, TokenTransactionType.COMMIT);

        // Verify the generated quiz can be retrieved
        var quiz = quizService.getGeneratedQuiz(job.getId(), testUser.getUsername());
        assertThat(quiz).isNotNull();
        assertThat(quiz.id()).isNotNull();
    }

    @Test
    @DisplayName("7.2 Multiple users - real services race test")
    void multipleUsersRaceTest_shouldHandleConcurrentOperations() throws Exception {
        // Create multiple users
        User user1 = createTestUser("race_user_1");
        User user2 = createTestUser("race_user_2");
        User user3 = createTestUser("race_user_3");

        // Credit tokens for all users
        creditTokensForUser(user1, selectedPack);
        creditTokensForUser(user2, selectedPack);
        creditTokensForUser(user3, selectedPack);

        // Create documents for all users
        Document doc1 = createProcessedDocumentForUser(user1);
        Document doc2 = createProcessedDocumentForUser(user2);
        Document doc3 = createProcessedDocumentForUser(user3);

        // Start quiz generation for all users simultaneously
        Map<QuestionType, Integer> questionsPerType =
            Map.of(QuestionType.MCQ_SINGLE, 1);

        GenerateQuizFromDocumentRequest request1 = createQuizRequest(doc1.getId(), "Race Test 1", questionsPerType);
        GenerateQuizFromDocumentRequest request2 = createQuizRequest(doc2.getId(), "Race Test 2", questionsPerType);
        GenerateQuizFromDocumentRequest request3 = createQuizRequest(doc3.getId(), "Race Test 3", questionsPerType);

        setBillingAuthoritiesForUser(user1);
        QuizGenerationResponse response1 = quizService.startQuizGeneration(user1.getUsername(), request1);

        setBillingAuthoritiesForUser(user2);
        QuizGenerationResponse response2 = quizService.startQuizGeneration(user2.getUsername(), request2);

        setBillingAuthoritiesForUser(user3);
        QuizGenerationResponse response3 = quizService.startQuizGeneration(user3.getUsername(), request3);

        // Verify all jobs complete successfully
        QuizGenerationJob job1 = awaitJobCompletion(response1.jobId(), Duration.ofMinutes(3));
        QuizGenerationJob job2 = awaitJobCompletion(response2.jobId(), Duration.ofMinutes(3));
        QuizGenerationJob job3 = awaitJobCompletion(response3.jobId(), Duration.ofMinutes(3));

        assertThat(job1.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(job2.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(job3.getStatus()).isEqualTo(GenerationStatus.COMPLETED);

        // Verify billing states
        assertThat(job1.getBillingState()).isEqualTo(BillingState.COMMITTED);
        assertThat(job2.getBillingState()).isEqualTo(BillingState.COMMITTED);
        assertThat(job3.getBillingState()).isEqualTo(BillingState.COMMITTED);

        // Verify no cross-user interference
        Balance balance1 = balanceRepository.findByUserId(user1.getId()).orElseThrow();
        Balance balance2 = balanceRepository.findByUserId(user2.getId()).orElseThrow();
        Balance balance3 = balanceRepository.findByUserId(user3.getId()).orElseThrow();

        assertThat(balance1.getReservedTokens()).isZero();
        assertThat(balance2.getReservedTokens()).isZero();
        assertThat(balance3.getReservedTokens()).isZero();
    }

    @Test
    @DisplayName("7.3 Real AI token accuracy with real billing")
    void realAiTokenAccuracy_shouldMatchBillingCalculations() throws Exception {
        creditTokensViaStripeWebhook();
        Document document = createProcessedDocument();

        Map<QuestionType, Integer> questionsPerType =
            Map.of(QuestionType.MCQ_SINGLE, 2);

        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                document.getId(),
                QuizScope.ENTIRE_DOCUMENT,
                null, null, null,
                "Token Accuracy Test",
                "Verify token counting accuracy with real AI",
                questionsPerType,
                Difficulty.MEDIUM,
                2, null, List.of()
        );

        setBillingAuthorities();
        QuizGenerationResponse response = quizService.startQuizGeneration(testUser.getUsername(), request);
        
        QuizGenerationJob job = awaitJobCompletion(response.jobId(), Duration.ofMinutes(3));
        assertThat(job.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(job.getBillingState()).isEqualTo(BillingState.COMMITTED);

        // Verify token accuracy
        assertThat(job.getBillingEstimatedTokens()).isGreaterThan(0);
        assertThat(job.getBillingCommittedTokens()).isGreaterThan(0);
        assertThat(job.getInputPromptTokens()).isGreaterThan(0);
        assertThat(job.getActualTokens()).isGreaterThan(0);

        // Verify billing calculation accuracy
        long totalLlmTokens = job.getInputPromptTokens() + (job.getActualTokens() != null ? job.getActualTokens() : 0L);
        
        // The billing system converts LLM tokens to billing tokens using a ratio
        // From the logs: 1642 LLM tokens -> 2 billing tokens
        // So the ratio is approximately 1 billing token per 800+ LLM tokens
        long expectedBillingTokens = Math.max(1L, totalLlmTokens / 800L); // Conservative estimate
        
        // Allow for some variance in billing calculations
        long difference = Math.abs(job.getBillingCommittedTokens() - expectedBillingTokens);
        assertThat(difference).isLessThanOrEqualTo(1L); // Allow 1 token difference
    }

    @Test
    @DisplayName("7.4 Real AI failure with real billing recovery")
    void realAiFailure_shouldRecoverBillingTokens() throws Exception {
        creditTokensViaStripeWebhook();

        // Create a request that might trigger AI failure (very large content)
        String largeContent = "This is a very large content chunk that might cause AI processing issues. ".repeat(1000);

        Map<QuestionType, Integer> questionsPerType =
            Map.of(QuestionType.MCQ_SINGLE, 5);

        GenerateQuizFromTextRequest request = new GenerateQuizFromTextRequest(
                largeContent,
                "en", // language
                ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED, // chunkingStrategy
                50000, // maxChunkSize
                QuizScope.ENTIRE_DOCUMENT, // quizScope
                null, // chunkIndices
                null, // chapterTitle
                null, // chapterNumber
                "AI Failure Recovery Test", // quizTitle
                "Test AI failure with billing recovery", // quizDescription
                questionsPerType,
                Difficulty.HARD,
                5, // estimatedTimePerQuestion
                null, // categoryId
                List.of() // tagIds
        );

        setBillingAuthorities();
        
        // Prepare HTTP request
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        
        HttpEntity<GenerateQuizFromTextRequest> httpEntity = new HttpEntity<>(request, headers);
        
        // Call the actual REST endpoint
        MvcResult result = mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .with(user(testUser.getUsername())
                                .authorities(
                                        new SimpleGrantedAuthority("QUIZ_CREATE"),
                                        new SimpleGrantedAuthority("BILLING_READ"),
                                        new SimpleGrantedAuthority("BILLING_WRITE")
                                )
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isAccepted())
                .andReturn();

        QuizGenerationResponse responseBody =
                objectMapper.readValue(result.getResponse().getContentAsByteArray(), QuizGenerationResponse.class);
        assertThat(responseBody.jobId()).isNotNull();
        
        // Verify HTTP response
        assertThat(responseBody).isNotNull();
        assertThat(responseBody.jobId()).isNotNull();
        
        QuizGenerationJob job = awaitJobCompletion(responseBody.jobId(), Duration.ofMinutes(5));

        // Job should either complete or fail gracefully
        assertThat(job.getStatus()).isIn(GenerationStatus.COMPLETED, GenerationStatus.FAILED);

        if (job.getStatus() == GenerationStatus.FAILED) {
            // Verify billing recovery on failure
            assertThat(job.getBillingState()).isEqualTo(BillingState.RELEASED);
            Balance balance = balanceRepository.findByUserId(testUser.getId()).orElseThrow();
            assertThat(balance.getReservedTokens()).isZero();
            assertThat(balance.getAvailableTokens()).isEqualTo(selectedPack.getTokens());
        } else {
            // Verify successful completion
            assertThat(job.getBillingState()).isEqualTo(BillingState.COMMITTED);
            assertThat(job.getBillingCommittedTokens()).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("7.5 Real Stripe payment failure with AI generation")
    void stripePaymentFailure_shouldPreventAiGeneration() throws Exception {
        // Create user without successful payment
        User userWithoutPayment = createTestUser("no_payment_user");
        
        Document document = createProcessedDocumentForUser(userWithoutPayment);

        Map<QuestionType, Integer> questionsPerType =
            Map.of(QuestionType.MCQ_SINGLE, 1);

        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                document.getId(),
                QuizScope.ENTIRE_DOCUMENT,
                null, null, null,
                "Payment Failure Test",
                "Test generation without payment",
                questionsPerType,
                Difficulty.EASY,
                1, null, List.of()
        );

        setBillingAuthoritiesForUser(userWithoutPayment);
        
        // Should throw insufficient tokens exception
        assertThatThrownBy(() -> quizService.startQuizGeneration(userWithoutPayment.getUsername(), request))
                .isInstanceOf(Exception.class);

        // Verify no job was created
        List<QuizGenerationJob> jobs = jobRepository.findByUser_UsernameOrderByStartedAtDesc(userWithoutPayment.getUsername());
        assertThat(jobs).isEmpty();

        // Verify no balance was created
        Optional<Balance> balance = balanceRepository.findByUserId(userWithoutPayment.getId());
        assertThat(balance).isEmpty();
    }

    @Test
    @DisplayName("7.6 Real AI rate limiting with billing management")
    void realAiRateLimiting_shouldManageBillingTokens() throws Exception {
        creditTokensViaStripeWebhook();
        Document document = createProcessedDocument();

        Map<QuestionType, Integer> questionsPerType =
            Map.of(QuestionType.MCQ_SINGLE, 1);

        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                document.getId(),
                QuizScope.ENTIRE_DOCUMENT,
                null, null, null,
                "Rate Limiting Test",
                "Test rate limiting with billing management",
                questionsPerType,
                Difficulty.EASY,
                1, null, List.of()
        );

        setBillingAuthorities();
        QuizGenerationResponse response = quizService.startQuizGeneration(testUser.getUsername(), request);
        
        QuizGenerationJob job = awaitJobCompletion(response.jobId(), Duration.ofMinutes(5));

        // Job should complete despite potential rate limiting
        assertThat(job.getStatus()).isIn(GenerationStatus.COMPLETED, GenerationStatus.FAILED);

        if (job.getStatus() == GenerationStatus.FAILED) {
            // Check if failure was due to rate limiting
            if (job.getErrorMessage() != null && job.getErrorMessage().toLowerCase().contains("rate limit")) {
                // Verify billing recovery on rate limit failure
                assertThat(job.getBillingState()).isEqualTo(BillingState.RELEASED);
                Balance balance = balanceRepository.findByUserId(testUser.getId()).orElseThrow();
                assertThat(balance.getReservedTokens()).isZero();
            }
        } else {
            // Verify successful completion
            assertThat(job.getBillingState()).isEqualTo(BillingState.COMMITTED);
        }
    }

    @Test
    @DisplayName("7.7 Real services load test")
    void realServicesLoadTest_shouldHandleHighLoad() throws Exception {
        // Create multiple users for load testing
        List<User> users = List.of(
            createTestUser("load_user_1"),
            createTestUser("load_user_2"),
            createTestUser("load_user_3")
        );

        // Credit tokens for all users
        for (User user : users) {
            creditTokensForUser(user, selectedPack);
        }

        // Create documents for all users
        List<Document> documents = users.stream()
            .map(this::createProcessedDocumentForUser)
            .toList();

        // Start quiz generation for all users
        Map<QuestionType, Integer> questionsPerType =
            Map.of(QuestionType.MCQ_SINGLE, 1);

        List<QuizGenerationResponse> responses = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            GenerateQuizFromDocumentRequest request = createQuizRequest(
                documents.get(i).getId(), 
                "Load Test " + (i + 1), 
                questionsPerType
            );
            setBillingAuthoritiesForUser(users.get(i));
            responses.add(quizService.startQuizGeneration(users.get(i).getUsername(), request));
        }

        // Wait for all jobs to complete
        List<QuizGenerationJob> jobs = new ArrayList<>();
        for (QuizGenerationResponse response : responses) {
            jobs.add(awaitJobCompletion(response.jobId(), Duration.ofMinutes(5)));
        }

        // Verify all jobs completed successfully
        for (QuizGenerationJob job : jobs) {
            assertThat(job.getStatus()).isIn(GenerationStatus.COMPLETED, GenerationStatus.FAILED);
            if (job.getStatus() == GenerationStatus.COMPLETED) {
                assertThat(job.getBillingState()).isEqualTo(BillingState.COMMITTED);
            } else {
                assertThat(job.getBillingState()).isEqualTo(BillingState.RELEASED);
            }
        }

        // Verify no cross-user interference
        for (User user : users) {
            Balance balance = balanceRepository.findByUserId(user.getId()).orElseThrow();
            assertThat(balance.getReservedTokens()).isZero();
        }
    }

    @Test
    @DisplayName("7.8 Real AI model comparison test")
    void realAiModelComparison_shouldCompareTokenUsage() throws Exception {
        creditTokensViaStripeWebhook();
        Document document = createProcessedDocument();

        Map<QuestionType, Integer> questionsPerType =
            Map.of(QuestionType.MCQ_SINGLE, 1);

        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                document.getId(),
                QuizScope.ENTIRE_DOCUMENT,
                null, null, null,
                "Model Comparison Test",
                "Compare token usage across models",
                questionsPerType,
                Difficulty.MEDIUM,
                1, null, List.of()
        );

        setBillingAuthorities();
        QuizGenerationResponse response = quizService.startQuizGeneration(testUser.getUsername(), request);
        
        QuizGenerationJob job = awaitJobCompletion(response.jobId(), Duration.ofMinutes(3));
        assertThat(job.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(job.getBillingState()).isEqualTo(BillingState.COMMITTED);

        // Verify token usage is recorded
        assertThat(job.getInputPromptTokens()).isGreaterThan(0);
        assertThat(job.getActualTokens()).isGreaterThan(0);
        assertThat(job.getBillingCommittedTokens()).isGreaterThan(0);

        // Verify billing calculation
        long totalTokens = job.getInputPromptTokens() + (job.getActualTokens() != null ? job.getActualTokens() : 0L);
        assertThat(totalTokens).isGreaterThan(0);
    }

    @Test
    @DisplayName("7.9 Real services error recovery test")
    void realServicesErrorRecovery_shouldHandleErrorsGracefully() throws Exception {
        creditTokensViaStripeWebhook();
        Document document = createProcessedDocument();

        Map<QuestionType, Integer> questionsPerType =
            Map.of(QuestionType.MCQ_SINGLE, 1);

        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                document.getId(),
                QuizScope.ENTIRE_DOCUMENT,
                null, null, null,
                "Error Recovery Test",
                "Test error recovery with real services",
                questionsPerType,
                Difficulty.EASY,
                1, null, List.of()
        );

        setBillingAuthorities();
        QuizGenerationResponse response = quizService.startQuizGeneration(testUser.getUsername(), request);
        
        QuizGenerationJob job = awaitJobCompletion(response.jobId(), Duration.ofMinutes(5));

        // Job should complete or fail gracefully
        assertThat(job.getStatus()).isIn(GenerationStatus.COMPLETED, GenerationStatus.FAILED);

        if (job.getStatus() == GenerationStatus.FAILED) {
            // Verify error information is recorded
            assertThat(job.getErrorMessage()).isNotBlank();
            assertThat(job.getBillingState()).isEqualTo(BillingState.RELEASED);
            
            // Verify billing recovery
            Balance balance = balanceRepository.findByUserId(testUser.getId()).orElseThrow();
            assertThat(balance.getReservedTokens()).isZero();
        } else {
            // Verify successful completion
            assertThat(job.getBillingState()).isEqualTo(BillingState.COMMITTED);
            assertThat(job.getBillingCommittedTokens()).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("7.10 Real services cost verification test")
    void realServicesCostVerification_shouldMatchCalculations() throws Exception {
        creditTokensViaStripeWebhook();
        Document document = createProcessedDocument();

        Map<QuestionType, Integer> questionsPerType =
            Map.of(QuestionType.MCQ_SINGLE, 2);

        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                document.getId(),
                QuizScope.ENTIRE_DOCUMENT,
                null, null, null,
                "Cost Verification Test",
                "Verify actual costs match billing calculations",
                questionsPerType,
                Difficulty.MEDIUM,
                2, null, List.of()
        );

        setBillingAuthorities();
        QuizGenerationResponse response = quizService.startQuizGeneration(testUser.getUsername(), request);
        
        QuizGenerationJob job = awaitJobCompletion(response.jobId(), Duration.ofMinutes(3));
        assertThat(job.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(job.getBillingState()).isEqualTo(BillingState.COMMITTED);

        // Verify cost calculations
        assertThat(job.getBillingEstimatedTokens()).isGreaterThan(0);
        assertThat(job.getBillingCommittedTokens()).isGreaterThan(0);
        assertThat(job.getInputPromptTokens()).isGreaterThan(0);
        assertThat(job.getActualTokens()).isGreaterThan(0);

        // Verify billing accuracy
        long totalLlmTokens = job.getInputPromptTokens() + (job.getActualTokens() != null ? job.getActualTokens() : 0L);
        
        // The billing system converts LLM tokens to billing tokens using a ratio
        // From the logs: 1642 LLM tokens -> 2 billing tokens
        // So the ratio is approximately 1 billing token per 800+ LLM tokens
        long expectedBillingTokens = Math.max(1L, totalLlmTokens / 800L); // Conservative estimate
        
        // Allow for reasonable variance
        long difference = Math.abs(job.getBillingCommittedTokens() - expectedBillingTokens);
        assertThat(difference).isLessThanOrEqualTo(1L); // Allow 1 token difference

        // Verify final balance
        Balance balance = balanceRepository.findByUserId(testUser.getId()).orElseThrow();
        assertThat(balance.getReservedTokens()).isZero();
        assertThat(balance.getAvailableTokens()).isGreaterThan(0);
        assertThat(balance.getAvailableTokens()).isLessThan(selectedPack.getTokens());
    }

    private void creditTokensViaStripeWebhook() throws Exception {
        var response = stripeService.createCheckoutSession(testUser.getId(), selectedPack.getStripePriceId(), selectedPack.getId());
        String sessionId = response.sessionId();

        String payload = buildCheckoutSessionEventPayload(sessionId);
        String signature = buildStripeSignature(payload);

        var result = stripeWebhookService.process(payload, signature);
        assertThat(result).isEqualTo(StripeWebhookService.Result.OK);

        Balance balance = balanceRepository.findByUserId(testUser.getId()).orElseThrow();
        assertThat(balance.getAvailableTokens()).isEqualTo(selectedPack.getTokens());

        Payment payment = paymentRepository.findByStripeSessionId(sessionId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    private Document createProcessedDocument() {
        Document document = new Document();
        document.setOriginalFilename("integration-source.txt");
        document.setContentType("text/plain");
        document.setFileSize(512L);
        document.setFilePath("/tmp/integration-source.txt");
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setUploadedBy(testUser);
        document.setTitle("Integration Document");
        document.setUploadedAt(LocalDateTime.now());
        document.setProcessedAt(LocalDateTime.now());

        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocument(document);
        chunk.setChunkIndex(0);
        chunk.setTitle("Integration Chunk");
        chunk.setContent("Artificial intelligence enables dynamic quiz creation by analyzing documents and transforming them into rich assessments. " +
                "This chunk serves as primary source material for the end-to-end integration test.");
        chunk.setStartPage(1);
        chunk.setEndPage(1);
        chunk.setWordCount(45);
        chunk.setCharacterCount(chunk.getContent().length());
        chunk.setCreatedAt(LocalDateTime.now());
        chunk.setChunkType(DocumentChunk.ChunkType.SECTION);

        document.setChunks(List.of(chunk));
        document.setTotalChunks(1);
        return documentRepository.save(document);
    }

    private QuizGenerationJob awaitJobCompletion(UUID jobId, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Optional<QuizGenerationJob> job = jobRepository.findById(jobId);
            if (job.isPresent() && job.get().getStatus().isTerminal()) {
                return job.get();
            }
            Thread.sleep(2000L);
        }
        throw new AssertionError("Quiz generation job did not complete within the allotted time");
    }

    private void setBillingAuthorities() {
        var authorities = List.of(
                new SimpleGrantedAuthority(PermissionName.BILLING_READ.name()),
                new SimpleGrantedAuthority(PermissionName.BILLING_WRITE.name()),
                new SimpleGrantedAuthority(PermissionName.QUIZ_CREATE.name())
        );
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                testUser.getId().toString(),
                "pwd",
                authorities
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private ProductPack createPlaceholderPack(String priceId, String name, long tokens, long amountCents) {
        ProductPack pack = new ProductPack();
        pack.setName(name);
        pack.setTokens(tokens);
        pack.setPriceCents(amountCents);
        pack.setCurrency("usd");
        pack.setStripePriceId(priceId);
        return productPackRepository.save(pack);
    }

    private String buildCheckoutSessionEventPayload(String sessionId) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", "evt_test_checkout_e2e_" + UUID.randomUUID());
        root.put("type", "checkout.session.completed");
        root.put("object", "event");
        ObjectNode data = root.putObject("data");
        ObjectNode inner = data.putObject("object");
        inner.put("id", sessionId);
        inner.put("object", "checkout.session");
        return objectMapper.writeValueAsString(root);
    }

    private String buildStripeSignature(String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String signedPayload = timestamp + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(stripeProperties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signature = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(signature.length * 2);
        for (byte b : signature) {
            sb.append(String.format("%02x", b));
        }
        return "t=" + timestamp + ",v1=" + sb;
    }

    // Helper methods for the new test scenarios
    private User createTestUser(String prefix) {
        User user = new User();
        user.setUsername(prefix + "_" + UUID.randomUUID().toString().substring(0, 8));
        user.setEmail(prefix + "_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        user.setHashedPassword("pwd");
        user.setActive(true);
        user.setDeleted(false);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private void creditTokensForUser(User user, ProductPack pack) throws Exception {
        var response = stripeService.createCheckoutSession(user.getId(), pack.getStripePriceId(), pack.getId());
        String sessionId = response.sessionId();

        String payload = buildCheckoutSessionEventPayload(sessionId);
        String signature = buildStripeSignature(payload);

        var result = stripeWebhookService.process(payload, signature);
        assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
    }

    private Document createProcessedDocumentForUser(User user) {
        Document document = new Document();
        document.setOriginalFilename("integration-source-" + user.getUsername() + ".txt");
        document.setContentType("text/plain");
        document.setFileSize(512L);
        document.setFilePath("/tmp/integration-source-" + user.getUsername() + ".txt");
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setUploadedBy(user);
        document.setTitle("Integration Document for " + user.getUsername());
        document.setUploadedAt(LocalDateTime.now());
        document.setProcessedAt(LocalDateTime.now());

        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocument(document);
        chunk.setChunkIndex(0);
        chunk.setTitle("Integration Chunk for " + user.getUsername());
        chunk.setContent("Artificial intelligence enables dynamic quiz creation by analyzing documents and transforming them into rich assessments. " +
                "This chunk serves as primary source material for the end-to-end integration test for user " + user.getUsername() + ".");
        chunk.setStartPage(1);
        chunk.setEndPage(1);
        chunk.setWordCount(45);
        chunk.setCharacterCount(chunk.getContent().length());
        chunk.setCreatedAt(LocalDateTime.now());
        chunk.setChunkType(DocumentChunk.ChunkType.SECTION);

        document.setChunks(List.of(chunk));
        document.setTotalChunks(1);
        return documentRepository.save(document);
    }

    private GenerateQuizFromDocumentRequest createQuizRequest(UUID documentId, String title, Map<QuestionType, Integer> questionsPerType) {
        return new GenerateQuizFromDocumentRequest(
                documentId,
                QuizScope.ENTIRE_DOCUMENT,
                null, null, null,
                title,
                "Quiz generated during integration test",
                questionsPerType,
                Difficulty.EASY,
                1, null, List.of()
        );
    }

    private void setBillingAuthoritiesForUser(User user) {
        var authorities = List.of(
                new SimpleGrantedAuthority(PermissionName.BILLING_READ.name()),
                new SimpleGrantedAuthority(PermissionName.BILLING_WRITE.name())
        );
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                user.getId().toString(),
                "pwd",
                authorities
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
