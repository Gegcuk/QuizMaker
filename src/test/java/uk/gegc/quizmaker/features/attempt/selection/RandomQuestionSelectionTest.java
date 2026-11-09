package uk.gegc.quizmaker.features.attempt.selection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.application.QuestionSelectionService;
import uk.gegc.quizmaker.features.attempt.application.impl.QuestionSelectionServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.*;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException;
import uk.gegc.quizmaker.features.attempt.infra.selection.HmacSelectionSeedSource;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.application.contract.v1.FamilyResponseContractV1;
import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.selection.QuestionSelectionFixtures.*;

class RandomQuestionSelectionTest {
    // Public synthetic fixture material, not an application secret.
    static final String FIXTURE_KEY = Base64.getEncoder().encodeToString(HexFormat.of()
            .parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"));
    static HmacSelectionSeedSource source() { return new HmacSelectionSeedSource("7", FIXTURE_KEY); }
    private final QuestionSelectionService service = new QuestionSelectionServiceImpl(source());

    @Test
    void fixedMetadataAndKey_repeatExactIndependentGoldenIdsOrderAndEvidenceAcrossNewInstances() {
        var request = randomInput(Configuration.randomCount(2), ITEMS, 545);
        var result = service.select(request);
        assertThat(result.itemIds()).containsExactly(MIDDLE, LAST);
        assertThat(result.evidence()).isEqualTo(new Evidence(1, 1, REVISION, 1, Configuration.randomCount(2),
                new RandomEvidence(1, request.context(), 7,
                        "10a1d4784e5807424c79c22b59d679229c0205cc77008b1de2cfe5b222cdf2fd", 3, 2)));
        for (int repeat = 0; repeat < 10; repeat++) {
            assertThat(service.select(request)).isEqualTo(result);
            assertThat(new QuestionSelectionServiceImpl(source()).select(request)).isEqualTo(result);
        }
        assertThatThrownBy(() -> result.itemIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fixedD38Bank_selectsExpectedTwentyAndFiveFamilyCompositionWithoutAnyQuotaInput() {
        var revision = d38Revision();
        var content = new ResolvedAttemptPolicy.ContentPolicy(REVISION, 1, ResolvedAttemptPolicy.FormMode.PRECOMPUTED,
                ResolvedAttemptPolicy.SectionMode.IMPLICIT_FLAT, ResolvedAttemptPolicy.CaseMode.NOT_CONFIGURED);
        var request = Input.fromRevision(content, revision, Configuration.randomCount(20), List.of(), context(6));
        var expected = IntStream.of(1000, 1004, 1005, 1009, 1011, 1013, 1014, 1015, 1017, 1018,
                1020, 1023, 1024, 1025, 1027, 1028, 1032, 1034, 1036, 1039).mapToObj(i -> new UUID(0, i)).toList();
        var result = service.select(request);
        assertThat(result.itemIds()).containsExactlyElementsOf(expected);
        assertThat(result.evidence().random().seedFingerprint())
                .isEqualTo("5de857dcbb5d3465fdab1186dea636c02514415d8121912febbee1020e699164");
        Map<QuestionType, Long> counts = new EnumMap<>(QuestionType.class);
        revision.items().stream().filter(item -> result.itemIds().contains(item.id()))
                .forEach(item -> counts.merge(item.question().type(), 1L, Long::sum));
        assertThat(counts).containsOnly(entry(QuestionType.MCQ_SINGLE, 8L), entry(QuestionType.MCQ_MULTI, 4L),
                entry(QuestionType.TRUE_FALSE, 4L), entry(QuestionType.FILL_GAP, 2L), entry(QuestionType.ORDERING, 2L));
        var reversed = new ArrayList<>(request.eligibleItems());
        Collections.reverse(reversed);
        assertThat(service.select(randomInput(request.configuration(), reversed, 6))).isEqualTo(result);
        // This is a real immutable-revision projection, not start/persistence or family-grading conformance.
        assertThat(BuiltInPracticePreset.V1.bindContent(content).questionSelection().mode())
                .isEqualTo(ResolvedAttemptPolicy.SelectionMode.ALL);
    }

    @ParameterizedTest
    @CsvSource({"10,3300,4", "3,5000,2", "3,1,1", "1,1,1", "0,10000,0", "10,0,0",
            "10,10000,10", "10001,1,2", "2147483647,10000,2147483647", "2147483647,9999,2147268899"})
    void percentageUsesExactCeilingWithoutFloatDriftOrOverflow(int candidates, int percentage, int expected) {
        assertThat(Configuration.randomPercentage(percentage).selectionSize(candidates)).isEqualTo(expected);
        if (candidates <= 10) {
            var result = service.select(randomInput(Configuration.randomPercentage(percentage), items(candidates), 6));
            assertThat(result.itemIds()).hasSize(expected);
            assertThat(result.evidence().random().selectedCount()).isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 10, 40, 257})
    void poolsBoundsAndServerContextsAlwaysProduceAnExactUniqueSubsetInAuthoredOrder(int poolSize) {
        var metadata = new ArrayList<>(items(poolSize));
        for (int operation = 1; operation <= 12; operation++) {
            var configurations = new ArrayList<>(List.of(Configuration.randomCount(0),
                    Configuration.randomCount(poolSize / 2), Configuration.randomCount(poolSize)));
            for (int percent : new int[]{0, 1, 100, 3300, 5000, 9999, 10000})
                configurations.add(Configuration.randomPercentage(percent));
            for (var configuration : configurations) {
                var result = service.select(randomInput(configuration, metadata, operation));
                int expected = configuration.strategy() == Strategy.RANDOM_COUNT ? configuration.count()
                        : (int) Math.ceil(poolSize * configuration.percentageBasisPoints() / 10000.0);
                assertThat(result.itemIds()).hasSize(expected).doesNotHaveDuplicates();
                assertThat(result.itemIds()).isSubsetOf(metadata.stream().map(Item::id).toList());
                assertThat(result.itemIds()).isSortedAccordingTo(Comparator.comparingLong(UUID::getLeastSignificantBits));
                Collections.reverse(metadata);
                assertThat(service.select(randomInput(configuration, metadata, operation))).isEqualTo(result);
            }
        }
    }

    @Test
    void invalidSizeAndUnavailableSeedFailWithoutFallbackOrChangesToTheInput() {
        var calls = new AtomicInteger();
        var selected = new QuestionSelectionServiceImpl(request -> {
            calls.incrementAndGet();
            throw new IllegalStateException("private-provider-detail");
        });
        var valid = randomInput(Configuration.randomCount(2), ITEMS, 545);
        assertThatThrownBy(() -> selected.select(randomInput(Configuration.randomCount(4), ITEMS, 545)))
                .isInstanceOfSatisfying(QuestionSelectionException.class, e -> assertThat(e.reason()).isEqualTo(INSUFFICIENT_CANDIDATES));
        assertThat(calls).hasValue(0);
        assertThatThrownBy(() -> selected.select(randomInput(Configuration.randomCount(1), List.of(), 545)))
                .hasMessage("Question selection rejected: INSUFFICIENT_CANDIDATES");
        assertThat(calls).hasValue(0);
        assertThatThrownBy(() -> selected.select(valid)).hasMessage("Question selection rejected: SEED_UNAVAILABLE").hasNoCause();
        assertThat(calls).hasValue(1);
        assertThat(valid.eligibleItems()).extracting(Item::id).containsExactly(FIRST, MIDDLE, LAST);
        assertThat(service.select(valid).itemIds()).containsExactly(MIDDLE, LAST);
        assertThatThrownBy(() -> new QuestionSelectionServiceImpl(request -> null).select(valid))
                .hasMessage("Question selection rejected: SEED_UNAVAILABLE");
    }

    @Test
    void missingOrMalformedConfigurationCannotBecomeClientSeedAuthorityOrSilentFallback() {
        assertThatThrownBy(() -> new Input(1, REVISION, 1, Configuration.randomCount(1), ITEMS, List.of()))
                .hasMessage("Question selection rejected: INVALID_SEED_CONTEXT");
        assertThatThrownBy(() -> new SelectionContext(1, null)).hasMessage("Question selection rejected: INVALID_SEED_CONTEXT");
        assertThatThrownBy(() -> new SelectionContext(2, new UUID(0, 1))).hasMessage("Question selection rejected: UNSUPPORTED_VERSION");
        assertThatThrownBy(() -> new SeedRequest(1, REVISION, 1, Configuration.all(), context(6)))
                .hasMessage("Question selection rejected: INVALID_SEED_CONTEXT");
        assertThatThrownBy(() -> new Input(1, REVISION, 1, Configuration.all(), ITEMS, List.of(), context(6)))
                .hasMessage("Question selection rejected: INVALID_SEED_CONTEXT");
        assertThatThrownBy(() -> Configuration.randomCount(-1)).hasMessage("Question selection rejected: INVALID_CONFIGURATION");
        for (int percent : new int[]{-1, 10001, Integer.MAX_VALUE})
            assertThatThrownBy(() -> Configuration.randomPercentage(percent)).hasMessage("Question selection rejected: INVALID_CONFIGURATION");
        for (var invalid : List.of(
                new Object[]{Strategy.RANDOM_COUNT, null, null}, new Object[]{Strategy.RANDOM_COUNT, 1, 1},
                new Object[]{Strategy.RANDOM_PERCENTAGE, 1, 1}, new Object[]{Strategy.RANDOM_PERCENTAGE, null, null},
                new Object[]{Strategy.ALL, 1, null}, new Object[]{Strategy.MANUAL_SET, null, 1})) {
            assertThatThrownBy(() -> new Configuration(1, (Strategy) invalid[0], 1, List.of(),
                    (Integer) invalid[1], (Integer) invalid[2])).hasMessage("Question selection rejected: INVALID_CONFIGURATION");
        }
        assertThatThrownBy(() -> new Configuration(1, Strategy.RANDOM_COUNT, 1, List.of(FIRST), 1, null))
                .hasMessage("Question selection rejected: INVALID_CONFIGURATION");
        assertThatThrownBy(() -> new Configuration(1, Strategy.RANDOM_COUNT, 2, List.of(), 1, null))
                .hasMessage("Question selection rejected: UNSUPPORTED_STRATEGY_VERSION");
        assertThatThrownBy(() -> randomInput(Configuration.randomCount(1), List.of(new Item(FIRST, 0), new Item(FIRST, 1)), 6))
                .hasMessage("Question selection rejected: DUPLICATE_ITEM");
        assertThatThrownBy(() -> randomInput(Configuration.randomCount(1), List.of(new Item(FIRST, 0), new Item(LAST, 0)), 6))
                .hasMessage("Question selection rejected: DUPLICATE_POSITION");
        assertThatThrownBy(() -> new Input(1, REVISION, 1, Configuration.randomCount(1), ITEMS, List.of(group(FIRST, LAST)), context(6)))
                .hasMessage("Question selection rejected: UNSUPPORTED_CONSTRAINT");
    }

    @Test
    void derivedEvidenceCannotClaimMissingSeedIncorrectCountOrDuplicates() {
        var result = service.select(randomInput(Configuration.randomCount(2), ITEMS, 545));
        var evidence = result.evidence();
        assertThatThrownBy(() -> new Evidence(1, 1, REVISION, 1, Configuration.randomCount(2)))
                .hasMessage("Question selection rejected: INVALID_RESULT");
        assertThatThrownBy(() -> new Evidence(1, 1, REVISION, 1, Configuration.all(), evidence.random()))
                .hasMessage("Question selection rejected: INVALID_RESULT");
        assertThatThrownBy(() -> new Result(1, evidence, List.of(FIRST)))
                .hasMessage("Question selection rejected: INVALID_RESULT");
        assertThatThrownBy(() -> new Result(1, evidence, List.of(FIRST, FIRST)))
                .hasMessage("Question selection rejected: DUPLICATE_ITEM");
        assertThatThrownBy(() -> new RandomEvidence(1, context(6), 7, "invalid", 3, 2))
                .hasMessage("Question selection rejected: INVALID_RESULT");
        assertThatThrownBy(() -> new RandomEvidence(1, context(6), 7, evidence.random().seedFingerprint(), 1, 2))
                .hasMessage("Question selection rejected: INVALID_RESULT");
    }

    @Test
    void oneSharedServiceIsDeterministicUnderConcurrentInvocations() throws Exception {
        var request = randomInput(Configuration.randomCount(20), items(40), 6);
        var expected = service.select(request);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var futures = IntStream.range(0, 32).mapToObj(i -> pool.submit(() -> service.select(request))).toList();
            for (var future : futures) assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void inputSeedAndOutputDiagnosticsOmitUnselectedBankAndSecretMaterial() {
        var request = randomInput(Configuration.randomCount(2), ITEMS, 545);
        var result = service.select(request);
        assertThat(request.toString()).doesNotContain(REVISION.toString(), FIRST.toString(), FIXTURE_KEY);
        assertThat(result.toString()).doesNotContain(FIRST.toString(), FIXTURE_KEY, "private-question", "private-answer");
        assertThat(result.evidence().random().toString()).doesNotContain(result.evidence().random().seedFingerprint());
    }

    static SelectionContext context(long operation) { return new SelectionContext(1, new UUID(0, operation)); }
    static Input randomInput(Configuration config, List<Item> metadata, long operation) {
        return new Input(1, REVISION, 1, config, metadata, List.of(), context(operation));
    }
    private static List<Item> items(int count) {
        return IntStream.range(0, count).mapToObj(i -> new Item(new UUID(0, 1000 + i), i)).toList();
    }
    private static QuizRevision d38Revision() {
        var timestamp = Instant.parse("2026-11-09T10:00:00Z");
        var items = IntStream.range(0, 40).mapToObj(i -> {
            QuestionType type = i < 16 ? QuestionType.MCQ_SINGLE : i < 24 ? QuestionType.MCQ_MULTI
                    : i < 32 ? QuestionType.TRUE_FALSE : i < 36 ? QuestionType.FILL_GAP : QuestionType.ORDERING;
            var content = QuestionContracts.example(type, QuestionContracts.View.AUTHOR);
            var options = new ArrayList<QuestionRevision.Option>();
            var source = content.path(type == QuestionType.ORDERING ? "items" : "options");
            if (source.isArray()) for (int index = 0; index < source.size(); index++) {
                String key = type == QuestionType.FILL_GAP ? "options/" + index : source.get(index).path("id").asText();
                options.add(new QuestionRevision.Option(new UUID(0, 100_000 + i * 100 + index), key, index));
            }
            var question = new QuestionRevision(new UUID(0, 2000 + i), new UUID(0, 3000 + i), 1, type,
                    Difficulty.MEDIUM, "Private fixture question", content.toString(), null, null, null, null, timestamp, options);
            new FamilyResponseContractV1(type).optionLayout(question);
            return new QuizRevision.Item(new UUID(0, 1000 + i), i, question);
        }).toList();
        return new QuizRevision(REVISION, new UUID(0, 50), new UUID(0, 60), 1, "D38 selection fixture", null, timestamp, items);
    }
}
