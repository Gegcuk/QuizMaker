package uk.gegc.quizmaker.features.documentProcess.application;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.document.application.DocumentIngestionMetrics;
import uk.gegc.quizmaker.features.documentProcess.application.impl.NormalizedDocumentAccessServiceImpl;
import uk.gegc.quizmaker.features.documentProcess.application.impl.StructureServiceImpl;
import uk.gegc.quizmaker.features.documentProcess.config.DocumentChunkingConfig;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.mapper.DocumentNodeMapper;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.ClockConfig;
import uk.gegc.quizmaker.shared.config.TransactionConfig;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Committed fixtures, real application transactions, and a fake only at the provider boundary. */
@Tag("db-serial")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.hikari.transaction-isolation=${structure.test.isolation:TRANSACTION_REPEATABLE_READ}",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=uk.gegc.quizmaker.features.documentProcess.application.StructureSqlCapture",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF",
        "logging.level.uk.gegc.quizmaker.features.documentProcess=WARN"
})
@Import({NormalizedDocumentAccessServiceImpl.class, StructureServiceImpl.class, DocumentQueryService.class,
        DocumentNodeMapper.class, AnchorOffsetCalculator.class, NodeHierarchyBuilder.class,
        ChunkedStructureService.class, DocumentChunker.class, TokenCounter.class,
        DocumentChunkingConfig.class, TransactionConfig.class, ClockConfig.class})
class NormalizedDocumentStructureBuildMySqlIntegrationTest {
    @Autowired NormalizedDocumentAccessService access;
    @Autowired NormalizedDocumentRepository documents;
    @Autowired UserRepository users;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;
    @Autowired DocumentChunkingConfig chunking;
    @Autowired ChunkedStructureService chunkedService;
    @MockitoSpyBean DocumentChunker chunker;
    @MockitoSpyBean DocumentNodeRepository nodes;
    @MockitoSpyBean AnchorOffsetCalculator offsets;
    @MockitoBean LlmClient provider;
    @MockitoBean DocumentIngestionService ingestion;
    @MockitoBean NormalizedDocumentFilePreparationService filePreparation;
    @MockitoBean NormalizedDocumentAccessMetrics metrics;
    @MockitoBean DocumentIngestionMetrics ingestionMetrics;

    private TransactionTemplate tx;
    private final List<ExecutorService> executors = new ArrayList<>();
    private final List<CountDownLatch> releases = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final java.util.Queue<UUID> fixtureDocuments = new ConcurrentLinkedQueue<>();
    private final java.util.Queue<UUID> fixtureOwners = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() {
        GuardedLlmClientDefaults.enable(provider);
        tx = new TransactionTemplate(transactionManager);
        calls.set(0);
        chunking.setMaxSingleChunkChars(150_000);
        when(provider.generateStructure(anyString(), any())).thenAnswer(call -> {
            assertProviderHasNoTransactionOrConnection();
            calls.incrementAndGet();
            return tree(call.getArgument(0));
        });
        when(provider.generateStructureWithContext(anyString(), any(), anyList(), anyInt(), anyInt()))
                .thenAnswer(call -> {
                    assertProviderHasNoTransactionOrConnection();
                    calls.incrementAndGet();
                    return tree(call.getArgument(0));
                });
    }

    @AfterEach
    void cleanUp() throws Exception {
        releases.forEach(CountDownLatch::countDown);
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        tx.executeWithoutResult(status -> {
            for (UUID id : fixtureDocuments) {
                nodes.deleteByDocument_Id(id);
                documents.findById(id).ifPresent(documents::delete);
            }
            for (UUID id : fixtureOwners) users.findById(id).ifPresent(users::delete);
        });
        StructureSqlCapture.clear();
    }

    @Test
    void firstBuildPublishesCompleteTreeWithoutReloadingSourceDuringVersionChecks() {
        Fixture f = fixture(3);
        StructureSqlCapture.clear();
        access.buildStructure(f.username(), f.id());
        List<String> sql = StructureSqlCapture.statements();
        assertThat(sql.stream().filter(s -> s.startsWith("select") && s.contains("normalized_text"))).hasSize(1);
        assertThat(calls).hasValue(1);
        State state = state(f);
        assertThat(state.status()).isEqualTo(NormalizedDocument.DocumentStatus.STRUCTURED);
        assertThat(state.version()).isEqualTo(1);
        assertThat(state.count()).isEqualTo(9);
        var roots = access.getTree(f.username(), f.id()).rootNodes();
        assertThat(roots).hasSize(3);
        for (int i = 0; i < roots.size(); i++) {
            assertThat(roots.get(i).idx()).isEqualTo(i);
            assertThat(roots.get(i).children()).singleElement().satisfies(section -> {
                assertThat(section.idx()).isZero();
                assertThat(section.children()).singleElement().satisfies(leaf -> assertThat(leaf.idx()).isZero());
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "soft-delete", "physical-delete", "new-owner"})
    void ownerMutationCommitsWhileProviderWaitsAndPublicationIsDenied(String mutation) throws Exception {
        Fixture f = fixture(2);
        CountDownLatch entered = new CountDownLatch(1), release = releaseLatch();
        blockProvider(entered, release);
        Future<Throwable> build = executor().submit(() -> build(f));
        try {
            await(entered);
            assertThat(dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
            executor().submit(() -> tx.executeWithoutResult(status -> {
                User owner = users.findById(f.ownerId()).orElseThrow();
                switch (mutation) {
                    case "inactive" -> owner.setActive(false);
                    case "soft-delete" -> owner.setDeleted(true);
                    case "physical-delete" -> users.delete(owner);
                    case "new-owner" -> documents.findById(f.id()).orElseThrow().setOwner(newOwner());
                    default -> throw new AssertionError(mutation);
                }
                users.flush();
            })).get(10, TimeUnit.SECONDS);
            // The mutation has committed before the remote call is allowed to finish.
        } finally { release.countDown(); }
        assertThat(build.get(10, TimeUnit.SECONDS)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Document not found");
        assertThat(state(f).count()).isZero();
        assertThat(state(f).status()).isEqualTo(NormalizedDocument.DocumentStatus.NORMALIZED);
    }

    @Test
    void changedSourceVersionRejectsStaleBuildWithoutOverwritingNewSource() throws Exception {
        Fixture f = fixture(1);
        CountDownLatch entered = new CountDownLatch(1), release = releaseLatch();
        blockProvider(entered, release);
        Future<Throwable> build = executor().submit(() -> build(f));
        try {
            await(entered);
            tx.executeWithoutResult(status -> documents.findById(f.id()).orElseThrow()
                    .setNormalizedText(text(2)));
        } finally { release.countDown(); }
        assertThat(build.get(10, TimeUnit.SECONDS)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Document changed during structure building");
        State state = state(f);
        assertThat(state.text()).isEqualTo(text(2));
        assertThat(state.version()).isEqualTo(1);
        assertThat(state.status()).isEqualTo(NormalizedDocument.DocumentStatus.NORMALIZED);
        assertThat(state.count()).isZero();
    }

    @Test
    void twoConcurrentBuildersHaveExactlyOneSuccessfulPublication() throws Exception {
        Fixture f = fixture(2);
        CountDownLatch entered = new CountDownLatch(2), release = releaseLatch();
        blockProvider(entered, release);
        Future<Throwable> a = executor().submit(() -> build(f));
        Future<Throwable> b = executor().submit(() -> build(f));
        try {
            await(entered);
            assertThat(dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
        } finally { release.countDown(); }
        List<Throwable> outcomes = Arrays.asList(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        assertThat(outcomes.stream().filter(Objects::isNull).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(Objects::nonNull).toList()).singleElement()
                .satisfies(failure -> assertThat(failure).isInstanceOf(IllegalStateException.class)
                        .hasMessage("Document changed during structure building"));
        assertThat(state(f).count()).isEqualTo(6);
        assertThat(state(f).version()).isEqualTo(1);
        assertThat(state(f).status()).isEqualTo(NormalizedDocument.DocumentStatus.STRUCTURED);
        assertCompleteTree(f, 2);
    }

    @Test
    void lateBuilderCannotOverwriteIndependentlyCommittedWinner() throws Exception {
        Fixture f = fixture(1);
        CountDownLatch entered = new CountDownLatch(1), release = releaseLatch();
        AtomicInteger dispatch = new AtomicInteger();
        when(provider.generateStructure(anyString(), any())).thenAnswer(call -> {
            assertProviderHasNoTransactionOrConnection();
            int number = dispatch.incrementAndGet();
            if (number == 1) { entered.countDown(); await(release); }
            List<DocumentNode> result = tree(call.getArgument(0));
            result.forEach(n -> n.setTitle("builder-" + number));
            return result;
        });
        Future<Throwable> late = executor().submit(() -> build(f));
        State winner;
        uk.gegc.quizmaker.features.documentProcess.api.dto.StructureTreeResponse winnerTree;
        try {
            await(entered);
            access.buildStructure(f.username(), f.id());
            winner = state(f);
            winnerTree = access.getTree(f.username(), f.id());
        } finally { release.countDown(); }
        assertThat(late.get(10, TimeUnit.SECONDS)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Document changed during structure building");
        assertThat(state(f)).isEqualTo(winner);
        assertThat(access.getTree(f.username(), f.id())).isEqualTo(winnerTree);
        assertThat(access.getFlat(f.username(), f.id()).nodes()).allSatisfy(n -> assertThat(n.title()).isEqualTo("builder-2"));
    }

    @Test
    void processingFailureAtDepthTwoHasNoPublicationOrPartialTree() {
        Fixture f = fixture(1);
        State before = state(f);
        AtomicBoolean reached = new AtomicBoolean();
        doAnswer(call -> {
            List<DocumentNode> level = call.getArgument(0);
            if (level.get(0).getDepth() == 2) {
                reached.set(true);
                throw new IllegalStateException("controlled depth-two fault");
            }
            return call.callRealMethod();
        }).when(offsets).calculateOffsets(anyList(), anyString());
        assertThatThrownBy(() -> access.buildStructure(f.username(), f.id())).isInstanceOf(IllegalStateException.class);
        assertThat(reached).isTrue();
        assertThat(state(f)).isEqualTo(before);
        verify(nodes, never()).saveAll(anyList());
    }

    @Test
    void publicationFailureAfterRealFlushRollsBackNodesStatusAndVersion() {
        Fixture f = fixture(2);
        State before = state(f);
        AtomicBoolean reached = new AtomicBoolean();
        doAnswer(call -> {
            entityManager.flush();
            assertThat(nodes.countByDocumentId(f.id())).isEqualTo(6);
            assertThat(documents.findSourceVersion(f.id())).contains(1L);
            // An independent connection cannot see the flushed but uncommitted publication.
            assertThat(executor().submit(() -> state(f)).get(10, TimeUnit.SECONDS)).isEqualTo(before);
            reached.set(true);
            throw new IllegalStateException("controlled publication fault before commit");
        }).when(nodes).flush();
        assertThatThrownBy(() -> access.buildStructure(f.username(), f.id())).isInstanceOf(IllegalStateException.class);
        assertThat(reached).isTrue();
        assertThat(state(f)).isEqualTo(before);
        // A supported retry after the rolled-back first build can still succeed.
        doAnswer(call -> { entityManager.flush(); return null; }).when(nodes).flush();
        access.buildStructure(f.username(), f.id());
        assertThat(state(f).count()).isEqualTo(6);
        assertThat(state(f).version()).isEqualTo(1);
    }

    @Test
    void wrongOwnerMissingPrincipalAndLegacyOwnerAreDeniedBeforeProviderDispatch() {
        Fixture f = fixture(1);
        for (String principal : Arrays.asList("another-owner", " ", null)) {
            assertThatThrownBy(() -> access.buildStructure(principal, f.id()))
                    .isInstanceOf(ResourceNotFoundException.class).hasMessage("Document not found");
        }
        tx.executeWithoutResult(status -> documents.findById(f.id()).orElseThrow().setOwner(null));
        assertThatThrownBy(() -> access.buildStructure(f.username(), f.id()))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Document not found");
        assertThat(calls).hasValue(0);
        assertThat(state(f).count()).isZero();
    }

    @Test
    void providerOutagePreservesSourceAndAllowsExplicitRetry() {
        Fixture f = fixture(1);
        State before = state(f);
        when(provider.generateStructure(anyString(), any())).thenThrow(new LlmClient.LlmException("provider unavailable"));
        assertThatThrownBy(() -> access.buildStructure(f.username(), f.id()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to generate document structure");
        assertThat(state(f)).isEqualTo(before);
        doAnswer(call -> tree(call.getArgument(0))).when(provider).generateStructure(anyString(), any());
        access.buildStructure(f.username(), f.id());
        assertThat(state(f).count()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realAdapterCompositionRechecksOwnerBeforeRetryWithASuccessfulControl(boolean revoke) {
        Fixture f = fixture(1);
        var chat = mock(org.springframework.ai.chat.model.ChatModel.class);
        var rate = mock(uk.gegc.quizmaker.shared.config.AiRateLimitConfig.class);
        when(rate.getMaxRetries()).thenReturn(2);
        var prompts = mock(DocumentStructurePromptService.class);
        when(prompts.buildStructurePrompt(anyString(), any(), anyList(), anyInt(), anyInt())).thenReturn("synthetic prompt");
        var adapter = new OpenAiLlmClient(chat, rate, prompts, chunking);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> adapter.generateStructure(call.getArgument(0), call.getArgument(1), call.getArgument(2)))
                .when(provider).generateStructure(anyString(), any(), any(Runnable.class));
        when(chat.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(call -> {
            assertProviderHasNoTransactionOrConnection();
            if (attempts.incrementAndGet() == 1) {
                if (revoke) tx.executeWithoutResult(status -> users.findById(f.ownerId()).orElseThrow().setActive(false));
                throw new IllegalStateException("controlled first provider attempt failure");
            }
            String json = """
                    {"nodes":[{"type":"chapter","title":"ROOT0","start_anchor":"ROOT0",
                    "end_anchor":"END0","depth":0,"confidence":0.9}]}
                    """;
            return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage(json))));
        });
        if (revoke) {
            assertThatThrownBy(() -> access.buildStructure(f.username(), f.id()))
                    .isInstanceOf(ResourceNotFoundException.class).hasMessage("Document not found");
            assertThat(state(f).count()).isZero();
            assertThat(state(f).status()).isEqualTo(NormalizedDocument.DocumentStatus.NORMALIZED);
        } else {
            access.buildStructure(f.username(), f.id());
            assertThat(state(f).count()).isEqualTo(1);
            assertThat(state(f).status()).isEqualTo(NormalizedDocument.DocumentStatus.STRUCTURED);
        }
        // The real adapter, application authorization and DB compose here; only ChatModel is remote-faked.
        verify(chat, times(revoke ? 1 : 2)).call(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test
    void repeatBuildRefusesBeforeProviderAndChangesNothing() {
        Fixture f = fixture(2);
        access.buildStructure(f.username(), f.id());
        State before = state(f);
        var treeBefore = access.getTree(f.username(), f.id());
        assertThatThrownBy(() -> access.buildStructure(f.username(), f.id()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("NORMALIZED");
        assertThat(calls).hasValue(1);
        assertThat(state(f)).isEqualTo(before);
        assertThat(access.getTree(f.username(), f.id())).isEqualTo(treeBefore);
    }

    @Test
    void repeatBuildAfterFreshPersistenceAndServiceRestartPreservesCommittedTree() {
        Fixture f = fixture(2);
        access.buildStructure(f.username(), f.id());
        State before = state(f);
        var treeBefore = access.getTree(f.username(), f.id());
        var bootstrap = new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();
        bootstrap.setDataSource(dataSource);
        bootstrap.setPackagesToScan("uk.gegc.quizmaker.features");
        bootstrap.setPersistenceUnitName("structure850-restart");
        bootstrap.setJpaVendorAdapter(new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());
        bootstrap.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
                "hibernate.dialect", "org.hibernate.dialect.MySQLDialect",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        bootstrap.afterPropertiesSet();
        try {
            var emf = Objects.requireNonNull(bootstrap.getObject());
            assertThat(emf).isNotSameAs(entityManagerFactory);
            var manager = new org.springframework.orm.jpa.JpaTransactionManager(emf);
            var transactions = new TransactionTemplate(manager);
            var shared = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(emf);
            var factory = new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(shared);
            var restartDocuments = factory.getRepository(NormalizedDocumentRepository.class);
            var restartNodes = factory.getRepository(DocumentNodeRepository.class);
            var restartUsers = factory.getRepository(UserRepository.class);
            var query = new DocumentQueryService(restartDocuments);
            var structures = transactional(StructureService.class, new StructureServiceImpl(restartNodes,
                    restartDocuments, new DocumentNodeMapper(), provider, new AnchorOffsetCalculator(),
                    new NodeHierarchyBuilder(), query, chunkedService, transactions, java.time.Clock.systemUTC()), manager);
            var restarted = transactional(NormalizedDocumentAccessService.class,
                    new NormalizedDocumentAccessServiceImpl(restartUsers, restartDocuments, ingestion, query,
                            structures, metrics, filePreparation, transactions, ingestionMetrics), manager);
            assertThatThrownBy(() -> restarted.buildStructure(f.username(), f.id()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("NORMALIZED");
            assertThat(restarted.getTree(f.username(), f.id())).isEqualTo(treeBefore);
            assertThat(state(f)).isEqualTo(before);
            assertThat(calls).hasValue(1);
        } finally { bootstrap.destroy(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unchanged", "revoked", "source-changed"})
    void laterChunkDispatchRechecksAuthorizationAndVersion(String change) {
        Fixture f = fixture(2);
        String text = text(2);
        int split = text.indexOf("ROOT1");
        chunking.setMaxSingleChunkChars(1);
        doReturn(List.of(new DocumentChunker.DocumentChunk(text.substring(0, split), 0, split, 0),
                new DocumentChunker.DocumentChunk(text.substring(split), split, text.length(), 1)))
                .when(chunker).chunkDocument(text, f.id().toString());
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            int attempt = calls.incrementAndGet();
            if (attempt == 1 && !change.equals("unchanged")) tx.executeWithoutResult(status -> {
                if (change.equals("revoked")) users.findById(f.ownerId()).orElseThrow().setActive(false);
                else documents.findById(f.id()).orElseThrow().setNormalizedText(text + "changed");
            });
            return tree(call.getArgument(0));
        }).when(provider).generateStructureWithContext(anyString(), any(), anyList(), anyInt(), anyInt());
        if (change.equals("unchanged")) {
            access.buildStructure(f.username(), f.id());
            assertThat(calls).hasValue(2);
            assertThat(state(f).status()).isEqualTo(NormalizedDocument.DocumentStatus.STRUCTURED);
            assertCompleteTree(f, 2);
        } else {
            assertThatThrownBy(() -> access.buildStructure(f.username(), f.id()))
                    .isInstanceOf(change.equals("revoked") ? ResourceNotFoundException.class : IllegalStateException.class);
            assertThat(calls).hasValue(1);
            assertThat(state(f).status()).isEqualTo(NormalizedDocument.DocumentStatus.NORMALIZED);
            assertThat(state(f).count()).isZero();
        }
    }

    @Test
    void ambientTransactionIsRejectedBeforeSourceOrProviderAccess() {
        Fixture f = fixture(1);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> access.buildStructure(f.username(), f.id())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(calls).hasValue(0);
        assertThat(state(f).count()).isZero();
    }

    @Test
    void connectionUsesTheRequestedIsolationLevel() {
        String configured = ((HikariDataSource) dataSource).getTransactionIsolation();
        int expected = switch (configured) {
            case "TRANSACTION_READ_COMMITTED" -> java.sql.Connection.TRANSACTION_READ_COMMITTED;
            case "TRANSACTION_REPEATABLE_READ" -> java.sql.Connection.TRANSACTION_REPEATABLE_READ;
            default -> throw new AssertionError("Unexpected test isolation: " + configured);
        };
        int actual = tx.execute(status -> entityManager.unwrap(org.hibernate.Session.class)
                .doReturningWork(java.sql.Connection::getTransactionIsolation));
        assertThat(actual).isEqualTo(expected);
    }

    private static <T> T transactional(Class<T> contract, Object target, PlatformTransactionManager manager) {
        var proxy = new org.springframework.aop.framework.ProxyFactory(target);
        proxy.setInterfaces(contract);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return contract.cast(proxy.getProxy());
    }

    @Test
    void treeAndFlatReadsHaveBoundedSqlAcrossMultipleDocumentsAndParents() {
        List<Fixture> small = List.of(fixture(1), fixture(1), fixture(1));
        List<Fixture> large = List.of(fixture(8), fixture(8), fixture(8));
        for (Fixture f : concat(small, large)) access.buildStructure(f.username(), f.id());
        long smallCount = readQueryCount(small);
        long largeCount = readQueryCount(large);
        assertThat(smallCount).isPositive();
        assertThat(largeCount).isEqualTo(smallCount);
        assertThat(largeCount).isLessThanOrEqualTo(21);
    }

    private long readQueryCount(List<Fixture> fixtures) {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        Map<UUID, Long> expected = new HashMap<>();
        for (Fixture f : fixtures) expected.put(f.id(), state(f).count());
        statistics.clear();
        for (Fixture f : fixtures) {
            assertThat(access.getTree(f.username(), f.id()).totalNodes()).isEqualTo(expected.get(f.id()));
            assertThat(access.getFlat(f.username(), f.id()).totalNodes()).isEqualTo(expected.get(f.id()));
        }
        return statistics.getPrepareStatementCount();
    }

    private void blockProvider(CountDownLatch entered, CountDownLatch release) {
        when(provider.generateStructure(anyString(), any())).thenAnswer(call -> {
            // Other workers may hold their own short transaction; this thread holds none.
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.incrementAndGet();
            entered.countDown();
            await(release);
            return tree(call.getArgument(0));
        });
    }

    private void assertProviderHasNoTransactionOrConnection() throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    private Throwable build(Fixture f) {
        try { access.buildStructure(f.username(), f.id()); return null; }
        catch (RuntimeException failure) { return failure; }
    }

    private Fixture fixture(int width) {
        return tx.execute(status -> {
            User owner = newOwner();
            NormalizedDocument d = new NormalizedDocument();
            d.setOwner(owner);
            d.setSource(NormalizedDocument.DocumentSource.TEXT);
            d.setNormalizedText(text(width));
            d.setCharCount(d.getNormalizedText().length());
            d.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
            documents.saveAndFlush(d);
            fixtureDocuments.add(d.getId());
            return new Fixture(owner.getId(), owner.getUsername(), d.getId());
        });
    }

    private User newOwner() {
        User u = new User();
        u.setUsername("structure850-" + UUID.randomUUID().toString().substring(0, 12));
        u.setEmail(u.getUsername() + "@example.invalid");
        u.setHashedPassword("unused-synthetic-hash");
        u.setPasswordChangedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        u.setActive(true);
        u.setDeleted(false);
        User saved = users.saveAndFlush(u);
        fixtureOwners.add(saved.getId());
        return saved;
    }

    private State state(Fixture f) {
        return tx.execute(status -> {
            NormalizedDocument d = documents.findById(f.id()).orElseThrow();
            return new State(d.getStatus(), d.getVersion(), d.getNormalizedText(), nodes.countByDocumentId(f.id()),
                    d.getOwner() == null ? null : d.getOwner().getId(), d.getCharCount(), d.getUpdatedAt());
        });
    }

    private void assertCompleteTree(Fixture f, int width) {
        var tree = access.getTree(f.username(), f.id());
        assertThat(tree.totalNodes()).isEqualTo(width * 3);
        assertThat(tree.rootNodes()).hasSize(width);
        for (int i = 0; i < width; i++) {
            int group = i;
            var root = tree.rootNodes().get(i);
            assertThat(root.title()).isEqualTo("ROOT" + group);
            assertThat(root.idx()).isEqualTo(group);
            assertThat(root.children()).singleElement().satisfies(section -> {
                assertThat(section.title()).isEqualTo("SECTION" + group);
                assertThat(section.children()).singleElement()
                        .satisfies(leaf -> assertThat(leaf.title()).isEqualTo("LEAF" + group));
            });
        }
    }

    private static String text(int width) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < width; i++) result.append("ROOT").append(i).append("\nSECTION").append(i)
                .append("\nLEAF").append(i).append("\nEND").append(i).append("\n");
        return result.toString();
    }

    private static List<DocumentNode> tree(String source) {
        List<DocumentNode> result = new ArrayList<>();
        var matcher = Pattern.compile("ROOT(\\d+)").matcher(source);
        while (matcher.find()) {
            String n = matcher.group(1);
            result.add(node("ROOT" + n, "END" + n, (short) 0));
            result.add(node("SECTION" + n, "END" + n, (short) 1));
            result.add(node("LEAF" + n, "END" + n, (short) 2));
        }
        return result;
    }

    private static DocumentNode node(String start, String end, short depth) {
        DocumentNode n = new DocumentNode();
        n.setTitle(start);
        n.setType(DocumentNode.NodeType.SECTION);
        n.setDepth(depth);
        n.setStartAnchor(start);
        n.setEndAnchor(end);
        return n;
    }

    private ExecutorService executor() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        return executor;
    }

    private CountDownLatch releaseLatch() {
        CountDownLatch latch = new CountDownLatch(1);
        releases.add(latch);
        return latch;
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).as("coordination reached").isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> all = new ArrayList<>(a); all.addAll(b); return all;
    }

    private record Fixture(UUID ownerId, String username, UUID id) { }
    private record State(NormalizedDocument.DocumentStatus status, long version, String text, long count,
                         UUID ownerId, Integer charCount, java.time.Instant updatedAt) { }
}
