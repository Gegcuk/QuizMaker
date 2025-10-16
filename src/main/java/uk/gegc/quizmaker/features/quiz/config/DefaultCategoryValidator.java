package uk.gegc.quizmaker.features.quiz.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;

import java.util.UUID;

/**
 * Verifies that the configured default category exists before the application serves traffic.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultCategoryValidator {

    private final QuizDefaultsProperties quizDefaultsProperties;
    private final CategoryRepository categoryRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void validateDefaultCategory() {
        UUID defaultCategoryId = quizDefaultsProperties.getDefaultCategoryId();
        if (!categoryRepository.existsById(defaultCategoryId)) {
            throw new IllegalStateException("Configured default category %s is missing".formatted(defaultCategoryId));
        }
        log.info("Validated default quiz category {}", defaultCategoryId);
    }
}

