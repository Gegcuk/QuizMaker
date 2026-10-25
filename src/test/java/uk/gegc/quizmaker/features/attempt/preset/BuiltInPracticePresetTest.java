package uk.gegc.quizmaker.features.attempt.preset;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.application.AttemptPresetService;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptPresetServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValidationException;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.*;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetResolutionException.Reason.*;

class BuiltInPracticePresetTest {
    private final AttemptPresetService presets = new AttemptPresetServiceImpl();
    private static final PresetReference V1 = new PresetReference("FREE_PRACTICE", 1);

    @Test
    void catalogueContainsExactlyOneImmutableVersionWithoutAliasesOrDuplicates() {
        assertThat(presets.supportedPresets()).containsExactly(V1).doesNotHaveDuplicates();
        assertThat(presets.load(new PresetReference("FREE_PRACTICE", 1)).reference()).isEqualTo(V1);
        assertThatThrownBy(() -> presets.supportedPresets().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void approvedTupleAndIndependentGoldenHashRemainStableAfterSerialization() throws Exception {
        // A fixture-only sorted JSON codec. #517 owns the production canonical policy format/hash.
        var codec = JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();
        var captured = new CapturedPreset(presets.load(V1).reference(), presets.load(V1).bindContent(content(42)));
        byte[] actual = codec.writeValueAsBytes(captured);
        try (var fixture = getClass().getResourceAsStream("/attempt-presets/free-practice-v1.json")) {
            assertThat(fixture).isNotNull();
            assertThat(codec.readTree(actual)).isEqualTo(codec.readTree(fixture));
        }
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual)))
                .isEqualTo("a6cfb22888318c519fc6b42f2a44e7344cb217f52dd4894f99b2223c6f3b5c23");
        var restored = codec.readValue(actual, CapturedPreset.class);
        assertThat(restored).isEqualTo(captured);
        assertThat(codec.writeValueAsBytes(restored)).containsExactly(actual);
        assertThat(codec.writeValueAsBytes(new CapturedPreset(presets.load(V1).reference(),
                presets.load(V1).bindContent(content(42))))).containsExactly(actual);
    }

    @Test
    void bindingAnotherRevisionCannotRewriteCapturedDefaults() {
        var preset = presets.load(V1);
        var captured = preset.bindContent(content(42));
        var later = preset.bindContent(content(43));
        assertThat(captured.content().quizRevisionId()).isEqualTo(new UUID(0, 42));
        assertThat(later.content().quizRevisionId()).isEqualTo(new UUID(0, 43));
        assertThat(captured).isEqualTo(presets.load(V1).bindContent(content(42)));
        assertThat(captured.submission().requireAllAnswers()).isFalse();
        assertThat(captured.submission().completionConfirmationRequired()).isTrue();
        assertThat(captured.results().passThresholdBasisPoints()).isEqualTo(7000);
        assertThat(captured.timing().mode()).isEqualTo(TimingMode.NONE);
        assertThatThrownBy(() -> captured.disclosure().rules().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> captured.review().content().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"free_practice", " FREE_PRACTICE", "FREE_PRACTICE ", "FREE_PRACTICE/v1", "FORMAL", "LATEST"})
    void unknownIdentifiersNeverSelectTheDefault(String identifier) {
        rejected(() -> presets.load(new PresetReference(identifier, 1)), UNSUPPORTED_PRESET);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 99, Integer.MAX_VALUE})
    void unknownSavedVersionsNeverLoadCurrentDefaults(int version) {
        rejected(() -> presets.load(new PresetReference("FREE_PRACTICE", version)), UNSUPPORTED_PRESET);
        assertThat(presets.load(V1).bindContent(content(42)).schemaVersion()).isEqualTo(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n", "\t"})
    void invalidReferencesHaveContentFreeErrors(String identifier) {
        rejected(() -> new PresetReference(identifier, 1), INVALID_REFERENCE);
    }

    @Test
    void invalidReferencesAndRevisionMetadataFailWithoutSubstitution() {
        rejected(() -> new PresetReference("x".repeat(129), 1), INVALID_REFERENCE);
        assertThat(new PresetReference("x".repeat(128), 1).id()).hasSize(128);
        for (int version : new int[]{Integer.MIN_VALUE, -1, 0}) {
            rejected(() -> new PresetReference("FREE_PRACTICE", version), INVALID_REFERENCE);
        }
        rejected(() -> presets.load(null), INVALID_REFERENCE);
        assertThatThrownBy(() -> presets.load(V1).bindContent(null)).isInstanceOf(PolicyValidationException.class);
        assertThatThrownBy(() -> new ContentPolicy(new UUID(0, 42), 2,
                FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED))
                .isInstanceOf(PolicyValidationException.class);
    }

    static ContentPolicy content(long revision) {
        return new ContentPolicy(new UUID(0, revision), 1,
                FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED);
    }

    static void rejected(Runnable action, PresetResolutionException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(PresetResolutionException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure).hasMessage("Attempt preset rejected: " + reason);
        });
    }

    private record CapturedPreset(PresetReference preset, ResolvedAttemptPolicy policy) {}
}
