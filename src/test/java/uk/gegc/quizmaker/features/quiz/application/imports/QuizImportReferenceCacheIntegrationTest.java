package uk.gegc.quizmaker.features.quiz.application.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.ImportSummaryDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizImportOptions;
import uk.gegc.quizmaker.features.quiz.domain.model.UpsertStrategy;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizImportAssembler;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(QuizImportReferenceCacheIntegrationTest.Configuration.class)
class QuizImportReferenceCacheIntegrationTest extends BaseIntegrationTest {
    @Autowired private QuizImportService importService;
    @Autowired private ReferenceResolutionService references;
    @Autowired private CategoryRepository categories;
    @Autowired private TagRepository tags;
    @Autowired private QuizRepository quizzes;
    @Autowired private UserRepository users;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ReferenceSql sql;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private QuizImportAssembler assembler;

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        ReferenceSql referenceSql() {
            return new ReferenceSql();
        }

        @Bean
        HibernatePropertiesCustomizer referenceInspector(ReferenceSql sql) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", sql);
        }
    }

    static class ReferenceSql implements StatementInspector {
        private final List<String> statements = new CopyOnWriteArrayList<>();
        private volatile Consumer<String> observer = ignored -> {};

        @Override
        public String inspect(String statement) {
            String normalized = statement.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
            observer.accept(normalized);
            if (normalized.startsWith("select ")
                    && (normalized.contains(" from categories ") || normalized.contains(" from tags "))) {
                statements.add(normalized);
            }
            return statement;
        }

        void clear() {
            statements.clear();
        }

        void assertBatches(int perType) {
            assertThat(statements).filteredOn(s -> s.contains(" from categories ")).hasSize(perType);
            assertThat(statements).filteredOn(s -> s.contains(" from tags ")).hasSize(perType);
            assertThat(statements).allSatisfy(s -> {
                assertThat(s).contains(" in (");
                assertThat(s.chars().filter(c -> c == '?').count()).isBetween(1L, 250L);
            });
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 100, 251})
    void existingReferencesUseBoundedSqlAcrossIndependentQuizTransactions(int size) throws Exception {
        User actor = actor();
        List<QuizImportDto> input = existingReferences(size);
        sql.clear();

        ImportSummaryDto result = run(actor, input, false, false, UpsertStrategy.CREATE_ONLY);

        assertThat(result.errors()).isEmpty();
        assertThat(result.created()).isEqualTo(size);
        sql.assertBatches((size + 249) / 250);
        assertThat(quizzes.findByCreatorId(actor.getId())).hasSize(size);
        // Count through SQL so relationship validation itself cannot hide lazy loads in the measured window.
        assertThat(jdbcTemplate.queryForObject("select count(*) from quiz_tags qt join quizzes q on q.quiz_id = qt.quiz_id "
                + "where q.creator_id = ?", Long.class, uuidBytes(actor.getId()))).isEqualTo((long) size);
    }

    @Test
    void upsertsReusePreloadWithoutAdditionalReferenceSelects() throws Exception {
        User actor = actor();
        assertThat(run(actor, existingReferences(10), false, false, UpsertStrategy.CREATE_ONLY).created()).isEqualTo(10);
        List<QuizImportDto> updates = quizzes.findByCreatorId(actor.getId()).stream().map(quiz -> {
            String name = categories.findById(quiz.getCategory().getId()).orElseThrow().getName();
            return quiz(quiz.getId(), name, name);
        }).toList();
        sql.clear();

        ImportSummaryDto result = run(actor, updates, false, false, UpsertStrategy.UPSERT_BY_ID);

        assertThat(result.errors()).isEmpty();
        assertThat(result.updated()).isEqualTo(10);
        assertThat(result.created()).isZero();
        sql.assertBatches(1);
    }

    @Test
    void dryRunReusesPreloadAndWritesNothing() throws Exception {
        User actor = actor();
        List<QuizImportDto> input = existingReferences(100);
        long categoryCount = categories.count();
        long tagCount = tags.count();
        long quizCount = quizzes.count();
        sql.clear();

        ImportSummaryDto result = run(actor, input, true, false, UpsertStrategy.CREATE_ONLY);

        assertThat(result.errors()).isEmpty();
        assertThat(result.total()).isEqualTo(100);
        assertThat(result.created()).isZero();
        assertThat(result.updated()).isZero();
        sql.assertBatches(1);
        assertThat(categories.count()).isEqualTo(categoryCount);
        assertThat(tags.count()).isEqualTo(tagCount);
        assertThat(quizzes.count()).isEqualTo(quizCount);
    }

    @Test
    void missingReferencesKeepAutoCreateAndDryRunRules() throws Exception {
        User actor = actor();
        String name = uniqueName();
        List<QuizImportDto> input = List.of(quiz(null, name, name));

        ImportSummaryDto missing = run(actor, input, false, false, UpsertStrategy.CREATE_ONLY);
        assertThat(missing.failed()).isEqualTo(1);
        assertThat(missing.errors().get(0).code()).isEqualTo("ResourceNotFoundException");
        ImportSummaryDto preview = run(actor, input, true, true, UpsertStrategy.CREATE_ONLY);
        assertThat(preview.errors()).isEmpty();
        assertThat(preview.created()).isZero();
        assertThat(categories.findByNameIgnoreCase(name)).isEmpty();
        assertThat(tags.findByNameInIgnoreCase(List.of(name))).isEmpty();

        ImportSummaryDto created = run(actor, input, false, true, UpsertStrategy.CREATE_ONLY);
        assertThat(created.errors()).isEmpty();
        assertThat(created.created()).isEqualTo(1);
        assertThat(categories.findByNameIgnoreCase(name)).isPresent();
        assertThat(tags.findByNameInIgnoreCase(List.of(name))).hasSize(1);
    }

    @Test
    void rolledBackItemCannotPoisonLaterItemWithUncommittedReferences() throws Exception {
        User importer = actor();
        String newReference = uniqueName();
        // The assembler rejects missing estimatedTime after resolving references inside the item transaction.
        QuizImportDto invalid = new QuizImportDto(1, null, "Missing duration", null, Visibility.PRIVATE,
                Difficulty.EASY, null, List.of(newReference), newReference, null, List.of(), null, null);
        List<QuizImportDto> input = List.of(invalid, quiz(null, newReference, newReference));

        ImportSummaryDto result = run(importer, input, false, true, UpsertStrategy.CREATE_ONLY);

        assertThat(result.created()).withFailMessage("Import result: %s", result).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).index()).isZero();
        assertThat(result.errors().get(0).code()).isEqualTo("ValidationException");
        assertThat(quizzes.findByCreatorId(importer.getId())).hasSize(1);
        assertThat(categories.findByNameIgnoreCase(newReference)).isPresent();
        assertThat(tags.findByNameInIgnoreCase(List.of(newReference))).hasSize(1);
        assertThat(quizzes.findByCreatorId(importer.getId()).get(0).getCategory().getId())
                .isEqualTo(categories.findByNameIgnoreCase(newReference).orElseThrow().getId());
    }

    @Test
    void nextImportSeesRenamedReferencesInsteadOfPreviousCache() throws Exception {
        User actor = actor();
        List<QuizImportDto> input = existingReferences(1);
        assertThat(run(actor, input, false, false, UpsertStrategy.CREATE_ONLY).created()).isEqualTo(1);
        String original = input.get(0).category().trim();
        Category renamed = categories.findByNameIgnoreCase(original).orElseThrow();
        renamed.setName(uniqueName());
        categories.saveAndFlush(renamed);

        ImportSummaryDto result = run(actor, input, false, false, UpsertStrategy.CREATE_ONLY);

        assertThat(result.created()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).code()).isEqualTo("ResourceNotFoundException");
    }

    @Test
    void concurrentImportsKeepSeparateReferenceInstances() throws Exception {
        String name = existingReferences(1).get(0).category().trim();
        CyclicBarrier barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Category> lookup = () -> {
                try {
                    var snapshot = references.preloadReferences(List.of(name), List.of());
                    barrier.await(10, TimeUnit.SECONDS);
                    Category resolved = references.resolveCategory(name, false, "user");
                    assertThat(resolved).isSameAs(snapshot.categories().get(name.toLowerCase(Locale.ROOT)));
                    return resolved;
                } finally {
                    references.clearCaches();
                }
            };
            var first = executor.submit(lookup);
            var second = executor.submit(lookup);
            assertThat(first.get(15, TimeUnit.SECONDS)).isNotSameAs(second.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void commitFailureEvictsNewReferencesAndNextItemRecovers() throws Exception {
        User actor = actor();
        String name = uniqueName();
        QuizImportDto commitFailure = quizFailingAtCommit(name);

        ImportSummaryDto result = run(actor, List.of(commitFailure, quiz(null, name, name)),
                false, true, UpsertStrategy.CREATE_ONLY);

        assertThat(result.created()).withFailMessage("%s", result).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).index()).isZero();
        assertThat(result.errors().get(0).code()).isEqualTo("DataIntegrityViolationException");
        assertThat(quizzes.findByCreatorId(actor.getId())).hasSize(1);
        assertThat(categories.findByNameIgnoreCase(name)).isPresent();
        assertThat(tags.findByNameInIgnoreCase(List.of(name))).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from quiz_tags qt join quizzes q on q.quiz_id = qt.quiz_id "
                + "where q.creator_id = ?", Long.class, uuidBytes(actor.getId()))).isEqualTo(1L);
    }

    @Test
    void laterRollbackDoesNotPoisonReferencesFromCommittedItem() throws Exception {
        User actor = actor();
        String name = uniqueName();
        QuizImportDto commitFailure = quizFailingAtCommit(name);

        ImportSummaryDto result = run(actor, List.of(quiz(null, name, name), commitFailure, quiz(null, name, name)),
                false, true, UpsertStrategy.CREATE_ONLY);

        assertThat(result.created()).withFailMessage("%s", result).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).index()).isEqualTo(1);
        assertThat(quizzes.findByCreatorId(actor.getId())).hasSize(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from quiz_tags qt join quizzes q on q.quiz_id = qt.quiz_id "
                + "where q.creator_id = ?", Long.class, uuidBytes(actor.getId()))).isEqualTo(2L);
    }

    @Test
    void outerRollbackDoesNotRollBackImportItemCommits() throws Exception {
        User actor = actor();
        String name = uniqueName();
        String outerOnlyName = uniqueName();
        var outer = new TransactionTemplate(transactionManager);
        ImportSummaryDto result = outer.execute(status -> {
            Category outerOnly = new Category();
            outerOnly.setName(outerOnlyName);
            categories.saveAndFlush(outerOnly);
            try {
                ImportSummaryDto imported = run(actor, List.of(quiz(null, name, name)), false, true, UpsertStrategy.CREATE_ONLY);
                status.setRollbackOnly();
                return imported;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });

        assertThat(result.errors()).isEmpty();
        assertThat(result.created()).isEqualTo(1);
        assertThat(categories.findByNameIgnoreCase(outerOnlyName)).isEmpty();
        assertThat(quizzes.findByCreatorId(actor.getId())).hasSize(1);
        assertThat(categories.findByNameIgnoreCase(name)).isPresent();
        assertThat(tags.findByNameInIgnoreCase(List.of(name))).hasSize(1);
    }

    @Test
    void parallelImportRollbackDoesNotPoisonOtherImportWithSameNewReferences() throws Exception {
        User rollbackActor = actor();
        User commitActor = actor();
        String name = uniqueName();
        CountDownLatch rollbackAtAssembler = new CountDownLatch(1);
        CountDownLatch commitAttemptingCategory = new CountDownLatch(1);
        AtomicReference<Thread> committingThread = new AtomicReference<>();
        doAnswer(call -> {
            QuizImportDto dto = call.getArgument(0);
            if (dto.title().equals("Import awaiting rollback")) {
                rollbackAtAssembler.countDown();
                assertThat(commitAttemptingCategory.await(20, TimeUnit.SECONDS)).isTrue();
            }
            // The real assembler rejects the first import's missing estimatedTime after reference writes.
            return call.callRealMethod();
        }).when(assembler).toEntity(any(), any(), any(), any(), any());
        sql.observer = statement -> {
            if (Thread.currentThread() == committingThread.get()
                    && statement.startsWith("insert into categories ")) {
                commitAttemptingCategory.countDown();
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var rolledBack = executor.submit(() -> {
                QuizImportDto invalid = new QuizImportDto(1, null, "Import awaiting rollback", null,
                        Visibility.PRIVATE, Difficulty.EASY, null, List.of(name), name, null, List.of(), null, null);
                return run(rollbackActor, List.of(invalid), false, true, UpsertStrategy.CREATE_ONLY);
            });
            var committed = executor.submit(() -> {
                committingThread.set(Thread.currentThread());
                assertThat(rollbackAtAssembler.await(20, TimeUnit.SECONDS)).isTrue();
                return run(commitActor, List.of(quiz(null, name, name), quiz(null, name, name)),
                        false, true, UpsertStrategy.CREATE_ONLY);
            });
            ImportSummaryDto failed = rolledBack.get(30, TimeUnit.SECONDS);
            ImportSummaryDto successful = committed.get(30, TimeUnit.SECONDS);
            assertThat(failed.created()).isZero();
            assertThat(failed.failed()).withFailMessage("%s", failed).isEqualTo(1);
            assertThat(failed.errors().get(0).code()).isEqualTo("ValidationException");
            assertThat(successful.errors()).withFailMessage("%s", successful).isEmpty();
            assertThat(successful.created()).isEqualTo(2);
            assertThat(quizzes.findByCreatorId(rollbackActor.getId())).isEmpty();
            assertThat(quizzes.findByCreatorId(commitActor.getId())).hasSize(2);
            assertThat(jdbcTemplate.queryForObject("select count(*) from quiz_tags qt join quizzes q on q.quiz_id = qt.quiz_id "
                    + "where q.creator_id = ?", Long.class, uuidBytes(commitActor.getId()))).isEqualTo(2L);
            assertThat(tags.findByNameInIgnoreCase(List.of(name))).hasSize(1);
            assertThat(categories.findByNameIgnoreCase(name)).isPresent();
        } finally {
            sql.observer = ignored -> {};
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void parallelTagCreationConflictDoesNotPoisonNextItem() throws Exception {
        User winnerActor = actor();
        User competingActor = actor();
        String category = existingReferences(1).get(0).category().trim();
        String tag = uniqueName();
        CountDownLatch loserAtAssembler = new CountDownLatch(1);
        CountDownLatch winnerFinished = new CountDownLatch(1);
        // Both imports resolve the missing tag before the winner commits. The other transaction
        // then fails on the unique constraint, and its next item must resolve the committed tag.
        doAnswer(call -> {
            QuizImportDto dto = call.getArgument(0);
            if (dto.title().equals("Winning import")) {
                assertThat(loserAtAssembler.await(20, TimeUnit.SECONDS)).isTrue();
            } else if (dto.title().equals("Conflicting item")) {
                loserAtAssembler.countDown();
                assertThat(winnerFinished.await(20, TimeUnit.SECONDS)).isTrue();
            }
            return call.callRealMethod();
        }).when(assembler).toEntity(any(), any(), any(), any(), any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var winner = executor.submit(() -> {
                try {
                    QuizImportDto dto = new QuizImportDto(1, null, "Winning import", null, Visibility.PRIVATE,
                            Difficulty.EASY, 10, List.of(tag), category, null, List.of(), null, null);
                    return run(winnerActor, List.of(dto), false, true, UpsertStrategy.CREATE_ONLY);
                } finally {
                    winnerFinished.countDown();
                }
            });
            var competing = executor.submit(() -> {
                QuizImportDto dto = new QuizImportDto(1, null, "Conflicting item", null, Visibility.PRIVATE,
                        Difficulty.EASY, 10, List.of(tag), category, null, List.of(), null, null);
                return run(competingActor, List.of(dto, quiz(null, category, tag)), false, true, UpsertStrategy.CREATE_ONLY);
            });
            ImportSummaryDto winningResult = winner.get(30, TimeUnit.SECONDS);
            ImportSummaryDto competingResult = competing.get(30, TimeUnit.SECONDS);
            assertThat(winningResult.errors()).isEmpty();
            assertThat(winningResult.created()).isEqualTo(1);
            assertThat(competingResult.created()).withFailMessage("%s", competingResult).isEqualTo(1);
            assertThat(competingResult.failed()).isEqualTo(1);
            assertThat(competingResult.errors().get(0).index()).isZero();
            assertThat(competingResult.errors().get(0).code()).isEqualTo("DataIntegrityViolationException");
            assertThat(quizzes.findByCreatorId(winnerActor.getId())).hasSize(1);
            assertThat(quizzes.findByCreatorId(competingActor.getId())).hasSize(1);
            assertThat(tags.findByNameInIgnoreCase(List.of(tag))).hasSize(1);
            assertThat(jdbcTemplate.queryForObject("select count(*) from quiz_tags qt join quizzes q on q.quiz_id = qt.quiz_id "
                    + "where q.creator_id = ?", Long.class, uuidBytes(competingActor.getId()))).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private QuizImportDto quizFailingAtCommit(String reference) {
        // Current validation checks the trimmed title, while the database stores the raw title.
        // This passes validation and triggers a real column-length failure during commit/flush.
        return new QuizImportDto(1, null, " ".repeat(101) + "Bad", null, Visibility.PRIVATE,
                Difficulty.EASY, 10, List.of(reference), reference, null, List.of(), null, null);
    }

    private List<QuizImportDto> existingReferences(int size) {
        String prefix = uniqueName();
        List<Category> categoryRows = new ArrayList<>();
        List<Tag> tagRows = new ArrayList<>();
        List<QuizImportDto> input = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String name = prefix + "-" + i;
            Category category = new Category();
            category.setName(name);
            categoryRows.add(category);
            Tag tag = new Tag();
            tag.setName(name);
            tagRows.add(tag);
            input.add(quiz(null, " " + name.toUpperCase(Locale.ROOT) + " ", " " + name.toUpperCase(Locale.ROOT) + " "));
        }
        categories.saveAllAndFlush(categoryRows);
        tags.saveAllAndFlush(tagRows);
        return input;
    }

    private ImportSummaryDto run(User actor, List<QuizImportDto> input, boolean dryRun, boolean autoCreate,
                                 UpsertStrategy strategy) throws Exception {
        return importService.importQuizzes(new ByteArrayInputStream(objectMapper.writeValueAsBytes(input)),
                ExportFormat.JSON_EDITABLE, new QuizImportOptions(strategy, dryRun, autoCreate, autoCreate, 1000),
                new UsernamePasswordAuthenticationToken(actor.getUsername(), "unused"));
    }

    private QuizImportDto quiz(UUID id, String category, String tag) {
        return new QuizImportDto(1, id, "Reference cache quiz", "Import fixture", Visibility.PRIVATE,
                Difficulty.EASY, 10, List.of(tag), category, null, List.of(), null, null);
    }

    private User actor() {
        String name = uniqueName();
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@example.invalid");
        user.setHashedPassword("unused-test-hash");
        return users.saveAndFlush(user);
    }

    private String uniqueName() {
        return "cache-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private byte[] uuidBytes(UUID id) {
        return java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
