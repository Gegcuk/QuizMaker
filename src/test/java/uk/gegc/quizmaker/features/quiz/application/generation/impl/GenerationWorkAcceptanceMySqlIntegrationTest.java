package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.ai.application.impl.PromptTemplateServiceImpl;
import uk.gegc.quizmaker.features.billing.application.*;
import uk.gegc.quizmaker.features.billing.application.impl.*;
import uk.gegc.quizmaker.features.billing.domain.model.*;
import uk.gegc.quizmaker.features.billing.infra.mapping.*;
import uk.gegc.quizmaker.features.billing.infra.repository.*;
import uk.gegc.quizmaker.features.document.application.*;
import uk.gegc.quizmaker.features.document.application.impl.DocumentProcessingServiceImpl;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.document.infra.mapping.DocumentMapper;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.application.generation.*;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizGenerationJobServiceImpl;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationRequestedEvent;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.*;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises acceptance through the public facade; no pre-created job, work, or reservation. */
@DataJpaTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create"})
@Import({QuizGenerationFacadeImpl.class, QuizGenerationJobServiceImpl.class,
        QuizGenerationIdempotencyServiceImpl.class, QuizGenerationRequestCanonicalizer.class,
        GenerationWorkServiceImpl.class, QuizGenerationCheckpointServiceImpl.class, QuizGenerationCheckpointCodec.class,
        BillingServiceImpl.class, InternalBillingServiceImpl.class, EstimationServiceImpl.class,
        GenerationTariffServiceImpl.class, PromptTemplateServiceImpl.class,
        DocumentProcessingServiceImpl.class, DocumentMapper.class, DocumentProcessingLimits.class,
        BalanceMapperImpl.class, ReservationMapperImpl.class, TokenTransactionMapperImpl.class,
        GenerationWorkAcceptanceMySqlIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("db-serial")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GenerationWorkAcceptanceMySqlIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean Clock systemClock() { return Clock.systemDefaultZone(); }
        @Bean QuizJobProperties properties() { return new QuizJobProperties(); }
        @Bean BillingProperties billingProperties() { return new BillingProperties(); }
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean MeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean FeatureFlags flags() { return new FeatureFlags(); }
        @Bean AcceptanceListener acceptanceListener(QuizGenerationJobRepository jobs, QuizGenerationWorkRepository works) {
            return new AcceptanceListener(jobs, works);
        }
    }

    static class AcceptanceListener implements ApplicationListener<QuizGenerationRequestedEvent> {
        private final QuizGenerationJobRepository jobs;
        private final QuizGenerationWorkRepository works;
        UUID jobId;
        UUID reservationId;
        boolean fail;

        AcceptanceListener(QuizGenerationJobRepository jobs, QuizGenerationWorkRepository works) {
            this.jobs = jobs;
            this.works = works;
        }

        @Override public void onApplicationEvent(QuizGenerationRequestedEvent event) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            jobId = event.getJobId();
            reservationId = jobs.findById(jobId).orElseThrow().getBillingReservationId();
            // The failure must happen after enrollment, rather than before its insert.
            assertThat(works.findById(jobId)).isPresent();
            if (fail) throw new IllegalStateException("synthetic acceptance failure after work registration");
        }
    }

    @Autowired QuizGenerationFacade facade;
    @Autowired QuizGenerationJobRepository jobs;
    @Autowired QuizGenerationWorkRepository works;
    @Autowired QuizGenerationOperationRepository operations;
    @Autowired ReservationRepository reservations;
    @Autowired BalanceRepository balances;
    @Autowired TokenTransactionRepository ledger;
    @Autowired UserRepository users;
    @Autowired DocumentRepository documents;
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManager entityManager;
    @Autowired AcceptanceListener listener;
    @MockitoBean AiQuizGenerationService ai;
    @MockitoBean QuizAssemblyService assembly;
    @MockitoBean BillingMetricsService billingMetrics;
    // Unused ingestion/storage boundaries: acceptance only reads an already processed source.
    @MockitoBean DocumentChunkingService chunking;
    @MockitoBean DocumentUploadStagingService staging;
    @MockitoBean DocumentParseExecutor parser;
    @MockitoBean DocumentDeletionService deletion;
    @MockitoBean DocumentIngestionMetrics ingestionMetrics;

    User owner;
    Document source;
    GenerateQuizFromDocumentRequest request;
    String key;

    @BeforeEach void prepareSourceAndBalance() {
        listener.jobId = null;
        listener.reservationId = null;
        listener.fail = false;
        key = "acceptance:" + UUID.randomUUID();
        transactions.executeWithoutResult(tx -> {
            owner = new User(); owner.setUsername("accept-" + UUID.randomUUID());
            owner.setEmail(owner.getUsername() + "@example.test"); owner.setHashedPassword("synthetic");
            users.saveAndFlush(owner);
            Balance balance = new Balance(); balance.setUserId(owner.getId()); balance.setAvailableTokens(1000);
            balances.saveAndFlush(balance);
            source = new Document(); source.setUploadedBy(owner); source.setOriginalFilename("synthetic.txt");
            source.setContentType("text/plain"); source.setFilePath("/synthetic/never-read"); source.setFileSize(1000L);
            source.setStatus(Document.DocumentStatus.PROCESSED); source.setContentRevision(7L); source.setTotalChunks(1);
            documents.saveAndFlush(source);
            DocumentChunk chunk = new DocumentChunk(); chunk.setDocument(source); chunk.setChunkIndex(0);
            chunk.setTitle("Synthetic source"); chunk.setContent("a".repeat(1000));
            chunk.setStartPage(1); chunk.setEndPage(1); chunk.setWordCount(1); chunk.setCharacterCount(1000);
            chunk.setCreatedAt(LocalDateTime.now()); entityManager.persist(chunk);
        });
        request = new GenerateQuizFromDocumentRequest(source.getId(), null, null, null, null, null, null,
                Map.of(QuestionType.TRUE_FALSE, 1), Difficulty.EASY, null, null, null, "en");
        when(ai.calculateTotalChunks(eq(source.getId()), eq(request))).thenReturn(1);
        when(ai.calculateEstimatedGenerationTime(eq(1), eq(request.questionsPerType()))).thenReturn(30);
    }

    @AfterEach void cleanup() {
        transactions.executeWithoutResult(tx -> {
            entityManager.createQuery("delete from QuizGenerationOperation o where o.userId = :owner")
                    .setParameter("owner", owner.getId()).executeUpdate();
            ledger.findByUserId(owner.getId()).forEach(ledger::delete);
            if (listener.jobId != null) {
                works.deleteById(listener.jobId);
                jobs.deleteById(listener.jobId);
            }
            entityManager.createQuery("delete from Reservation r where r.userId = :owner")
                    .setParameter("owner", owner.getId()).executeUpdate();
            balances.deleteById(owner.getId());
            entityManager.createQuery("delete from DocumentChunk c where c.document.id = :source")
                    .setParameter("source", source.getId()).executeUpdate();
            documents.deleteById(source.getId());
            users.deleteById(owner.getId());
        });
    }

    @Test void acceptedRequestCommitsJobWorkReservationAndOperationLinkTogether() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var response = facade.startQuizGeneration(owner.getUsername(), request, key);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        inNewTransaction(() -> {
            var job = jobs.findById(response.jobId()).orElseThrow();
            var work = works.findById(response.jobId()).orElseThrow();
            var reservation = reservations.findById(job.getBillingReservationId()).orElseThrow();
            var operation = operations.findByUserIdAndOperationTypeAndIdempotencyKey(
                    owner.getId(), GenerationOperationType.DOCUMENT, key).orElseThrow();
            assertThat(listener.jobId).isEqualTo(response.jobId());
            assertThat(job.getUser().getId()).isEqualTo(owner.getId());
            assertThat(job.getDocumentId()).isEqualTo(source.getId());
            assertThat(job.getStatus()).isEqualTo(GenerationStatus.PENDING);
            assertThat(job.getBillingState()).isEqualTo(BillingState.RESERVED);
            assertThat(job.getBillingEstimatedTokens()).isEqualTo(4L);
            assertThat(work.getState()).isEqualTo(QuizGenerationWork.State.READY);
            assertThat(work.getSourceRevision()).isEqualTo(7L);
            assertThat(work.getExecutions()).isZero();
            assertThat(reservation.getJobId()).isEqualTo(response.jobId());
            assertThat(reservation.getUserId()).isEqualTo(owner.getId());
            assertThat(reservation.getState()).isEqualTo(ReservationState.ACTIVE);
            assertThat(reservation.getEstimatedTokens()).isEqualTo(4L);
            assertThat(reservation.getCommittedTokens()).isZero();
            assertThat(operation.getState()).isEqualTo(GenerationOperationState.STARTED);
            assertThat(operation.getJobId()).isEqualTo(response.jobId());
            assertThat(operation.getReservationId()).isEqualTo(reservation.getId());
            assertBalance(996, 4);
            assertThat(ledger.findByUserId(owner.getId())).singleElement().satisfies(entry -> {
                assertThat(entry.getType()).isEqualTo(TokenTransactionType.RESERVE);
                // RESERVE moves balance into escrow; the ledger amount is charged only on COMMIT.
                assertThat(entry.getAmountTokens()).isZero();
                assertThat(entry.getRefId()).isEqualTo(reservation.getId().toString());
                assertThat(entry.getIdempotencyKey()).isEqualTo("quiz-generation:reserve:" + operation.getId());
                assertThat(entry.getBalanceAfterAvailable()).isEqualTo(996L);
                assertThat(entry.getBalanceAfterReserved()).isEqualTo(4L);
            });
        });
        verifyGenerationWasNotExecuted();
    }

    @Test void failureAfterEnrollmentRollsBackAcceptanceButRetainsAnUnlinkedIdempotencyOperation() {
        listener.fail = true;
        assertThatThrownBy(() -> facade.startQuizGeneration(owner.getUsername(), request, key))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("synthetic acceptance failure after work registration");
        assertThat(listener.jobId).isNotNull();
        assertThat(listener.reservationId).isNotNull();
        inNewTransaction(() -> {
            assertThat(jobs.existsById(listener.jobId)).isFalse();
            assertThat(works.existsById(listener.jobId)).isFalse();
            assertThat(reservations.existsById(listener.reservationId)).isFalse();
            assertThat(entityManager.createQuery("select count(j) from QuizGenerationJob j where j.user.id = :owner", Long.class)
                    .setParameter("owner", owner.getId()).getSingleResult()).isZero();
            assertThat(entityManager.createQuery("select count(r) from Reservation r where r.userId = :owner", Long.class)
                    .setParameter("owner", owner.getId()).getSingleResult()).isZero();
            assertThat(ledger.findByUserId(owner.getId())).isEmpty();
            assertBalance(1000, 0);
            var operation = operations.findByUserIdAndOperationTypeAndIdempotencyKey(
                    owner.getId(), GenerationOperationType.DOCUMENT, key).orElseThrow();
            assertThat(operation.getState()).isEqualTo(GenerationOperationState.READY_TO_START);
            assertThat(operation.getJobId()).isNull();
            assertThat(operation.getReservationId()).isNull();
            assertThat(operation.getEstimatedTimeSeconds()).isNull();
        });
        verifyGenerationWasNotExecuted();
    }

    private void inNewTransaction(Runnable assertions) {
        var read = new TransactionTemplate(transactions.getTransactionManager());
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.executeWithoutResult(tx -> assertions.run());
    }

    private void assertBalance(long available, long reserved) {
        var balance = balances.findByUserId(owner.getId()).orElseThrow();
        assertThat(balance.getAvailableTokens()).isEqualTo(available);
        assertThat(balance.getReservedTokens()).isEqualTo(reserved);
    }

    private void verifyGenerationWasNotExecuted() {
        verify(ai, never()).generateQuizFromDocumentAsync(any(UUID.class), any());
        verify(ai, never()).generateQuizFromDocumentAsync(any(QuizGenerationJob.class), any());
        verifyNoInteractions(assembly, staging, parser, chunking, deletion);
    }
}
