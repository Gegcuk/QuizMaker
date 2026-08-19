package uk.gegc.quizmaker.shared.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Generation language policy")
class GenerationLanguagePolicyTest {

    @Test
    @DisplayName("Defaults only an absent language to English")
    void defaultsOnlyAbsentLanguage() {
        assertThat(GenerationLanguagePolicy.requireSupportedOrDefault(null)).isEqualTo("en");
    }

    @ParameterizedTest(name = "language={0}")
    @ValueSource(strings = {"en", "fr", "de", "es", "ja", "uk"})
    @DisplayName("Preserves supported lowercase ISO 639-1 languages")
    void preservesSupportedLowercaseLanguages(String language) {
        assertThat(GenerationLanguagePolicy.requireSupportedOrDefault(language)).isEqualTo(language);
    }

    @ParameterizedTest(name = "invalid language [{0}]")
    @MethodSource("invalidLanguages")
    @DisplayName("Rejects supplied values instead of normalizing them")
    void rejectsInvalidSuppliedLanguages(String language) {
        assertThatThrownBy(() -> GenerationLanguagePolicy.requireSupportedOrDefault(language))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(GenerationLanguagePolicy.INVALID_LANGUAGE_MESSAGE);
    }

    private static Stream<String> invalidLanguages() {
        return Stream.of(
                "",
                " ",
                "EN",
                "English",
                "en-US",
                "zh-Hant",
                "en_GB",
                " fr",
                "fr ",
                "zz",
                "e\nn",
                "e\u0000n"
        );
    }
}
