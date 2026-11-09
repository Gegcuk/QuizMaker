package uk.gegc.quizmaker.features.attempt.selection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import uk.gegc.quizmaker.features.attempt.application.QuestionSelectionService;
import uk.gegc.quizmaker.features.attempt.application.impl.QuestionSelectionServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.*;
import uk.gegc.quizmaker.features.attempt.infra.selection.HmacSelectionSeedSource;

import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.selection.QuestionSelectionFixtures.*;
import static uk.gegc.quizmaker.features.attempt.selection.RandomQuestionSelectionTest.*;

class HmacSelectionSeedSourceTest {
    private static SeedRequest request() { return new SeedRequest(1, REVISION, 1, Configuration.randomCount(2), context(545)); }

    @Test
    void knownServerKeyAndContextDeriveIndependentHmacGoldenAndNeverExposeMaterialInDiagnostics() {
        var seed = source().derive(request());
        assertThat(HexFormat.of().formatHex(seed.bytes()))
                .isEqualTo("9c48b5c366f60f1297bed7c4e891ccf8d23de459c019e516a360f8e86ffd869b");
        assertThat(seed.keyVersion()).isEqualTo(7);
        var copied = seed.bytes();
        Arrays.fill(copied, (byte) 0);
        assertThat(seed.bytes()).isNotEqualTo(copied);
        var supplied = new byte[32];
        var isolated = new SeedMaterial(1, supplied);
        supplied[0] = 1;
        assertThat(isolated.bytes()[0]).isZero();
        assertThat(seed.toString()).isEqualTo("SeedMaterial[redacted]");
        assertThat(request().toString()).doesNotContain(REVISION.toString(), new UUID(0, 545).toString(), FIXTURE_KEY);
    }

    @Test
    void reusedOperationCannotOverrideRevisionConfigurationStrategyOrKeyVersion() {
        var first = source().derive(request()).bytes();
        for (var alternative : List.of(
                new SeedRequest(1, FOREIGN, 1, Configuration.randomCount(2), context(545)),
                new SeedRequest(1, REVISION, 1, Configuration.randomCount(1), context(545)),
                new SeedRequest(1, REVISION, 1, Configuration.randomPercentage(5000), context(545)),
                new SeedRequest(1, REVISION, 1, Configuration.randomCount(2), context(546)))) {
            assertThat(source().derive(alternative).bytes()).isNotEqualTo(first);
        }
        assertThat(new HmacSelectionSeedSource("8", FIXTURE_KEY).derive(request()).bytes()).isNotEqualTo(first);
        var alternateKey = Base64.getEncoder().encodeToString(new byte[32]);
        assertThat(new HmacSelectionSeedSource("7", alternateKey).derive(request()).bytes()).isNotEqualTo(first);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-base64", "private-seed-detail", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void missingOrMalformedKeyIsUnavailableWithoutBreakingUnrelatedStrategies(String key) {
        var source = new HmacSelectionSeedSource("7", key);
        assertThatThrownBy(() -> source.derive(request())).hasMessage("Question selection rejected: SEED_UNAVAILABLE").hasNoCause();
        var service = new QuestionSelectionServiceImpl(source);
        assertThat(service.select(input(Configuration.all())).itemIds()).containsExactlyElementsOf(ORDERED);
        assertThat(service.select(input(Configuration.manualSet(List.of(FIRST)))).itemIds()).containsExactly(FIRST);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "-1", "2147483648", "1secret", "99999999999"})
    void invalidKeyVersionCannotProduceUnversionedOrLeakedSeed(String version) {
        assertThatThrownBy(() -> new HmacSelectionSeedSource(version, FIXTURE_KEY).derive(request()))
                .hasMessage("Question selection rejected: SEED_UNAVAILABLE").hasNoCause();
    }

    @Test
    void invalidRequestOrSeedMaterialFailsAtTheOwnedBoundary() {
        assertThatThrownBy(() -> source().derive(null)).hasMessage("Question selection rejected: INVALID_SEED_CONTEXT");
        assertThatThrownBy(() -> new SeedMaterial(0, new byte[32])).hasMessage("Question selection rejected: SEED_UNAVAILABLE");
        assertThatThrownBy(() -> new SeedMaterial(1, null)).hasMessage("Question selection rejected: SEED_UNAVAILABLE");
        assertThatThrownBy(() -> new SeedMaterial(1, new byte[31])).hasMessage("Question selection rejected: SEED_UNAVAILABLE");
    }

    @Test
    void realSpringWiringSupportsConfiguredRandomSelectionAndMissingKeyStillSupportsLegacySelection() {
        for (boolean configured : new boolean[]{false, true}) {
            try (var spring = new AnnotationConfigApplicationContext()) {
                if (configured) spring.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",
                        Map.of("quizmaker.attempt.selection.seed-key-version", "7", "quizmaker.attempt.selection.seed-key-base64", FIXTURE_KEY)));
                spring.register(QuestionSelectionServiceImpl.class, HmacSelectionSeedSource.class);
                spring.refresh();
                var service = spring.getBean(QuestionSelectionService.class);
                assertThat(service.select(input(Configuration.all())).itemIds()).containsExactlyElementsOf(ORDERED);
                var request = randomInput(Configuration.randomCount(2), ITEMS, 545);
                if (configured) assertThat(service.select(request).itemIds()).containsExactly(MIDDLE, LAST);
                else assertThatThrownBy(() -> service.select(request)).hasMessage("Question selection rejected: SEED_UNAVAILABLE");
            }
        }
    }
}
