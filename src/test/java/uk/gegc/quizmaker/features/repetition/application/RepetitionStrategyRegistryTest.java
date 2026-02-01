package uk.gegc.quizmaker.features.repetition.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionContentType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@DisplayName("RepetitionStrategyRegistry Tests")
class RepetitionStrategyRegistryTest extends BaseUnitTest {

    @Mock private RepetitionContentStrategy questionStrategy;

    private RepetitionStrategyRegistry registry;

    @BeforeEach
    void setUp() {
        when(questionStrategy.supportedType()).thenReturn(RepetitionContentType.QUESTION);
        registry = new RepetitionStrategyRegistry(List.of(questionStrategy));
    }

    @Test
    @DisplayName("Should return correct strategy for supported type")
    void shouldReturnCorrectStrategy() {
        RepetitionContentStrategy result = registry.get(RepetitionContentType.QUESTION);

        assertSame(questionStrategy, result);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for missing type")
    void shouldThrowForMissingType() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registry.get(RepetitionContentType.QUIZ));

        assertEquals("No strategy for type QUIZ", ex.getMessage());
    }
}