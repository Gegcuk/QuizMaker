package uk.gegc.quizmaker.features.ai.application.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.support.AnnotationSupport;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RealAiQuizGenerationIntegrationTestExecutionContractTest {

    @Test
    @DisplayName("live OpenAI smoke test requires both the provider and database lanes")
    void realAiQuizGenerationIntegrationTest_hasExplicitExecutionTags() {
        Set<String> tags = AnnotationSupport
                .findRepeatableAnnotations(RealAiQuizGenerationIntegrationTest.class, Tag.class)
                .stream()
                .map(Tag::value)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(tags).contains("db-serial", "real-provider");
    }
}
