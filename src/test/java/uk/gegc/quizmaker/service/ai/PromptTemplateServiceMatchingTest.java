package uk.gegc.quizmaker.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gegc.quizmaker.features.ai.application.impl.PromptTemplateServiceImpl;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Prompt template service matching contract")
class PromptTemplateServiceMatchingTest {

    @Mock
    private ResourceLoader resourceLoader;

    @Mock private Resource contextTemplateResource;
    @Mock private Resource matchingTemplateResource;

    @InjectMocks
    private PromptTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "templateCache", new ConcurrentHashMap<>());
    }

    @Test
    @DisplayName("Places the rendered matching contract before the delimited source")
    void buildsMatchingPrompt_usesMatchingTemplate() throws IOException {
        when(resourceLoader.getResource("classpath:prompts/base/context-template.txt"))
                .thenReturn(contextTemplateResource);
        when(contextTemplateResource.getInputStream())
                .thenReturn(new ByteArrayInputStream(
                        "TRUSTED CONTEXT {language}".getBytes(StandardCharsets.UTF_8)));

        when(resourceLoader.getResource("classpath:prompts/question-types/matching.txt"))
                .thenReturn(matchingTemplateResource);
        when(matchingTemplateResource.getInputStream())
                .thenReturn(new ByteArrayInputStream(
                        "MATCHING CONTRACT type={questionType} count={questionCount} difficulty={difficulty}"
                                .getBytes(StandardCharsets.UTF_8)));

        String prompt = service.buildPromptForChunk("ABC", QuestionType.MATCHING, 2, Difficulty.EASY, "fr");

        assertNotNull(prompt);
        assertTrue(prompt.contains("TRUSTED CONTEXT fr"));
        assertTrue(prompt.contains("MATCHING CONTRACT type=MATCHING count=2 difficulty=EASY"));

        int matchingContractIndex = prompt.indexOf("MATCHING CONTRACT");
        int untrustedSourceSectionIndex = prompt.indexOf("UNTRUSTED DOCUMENT SOURCE:");
        int sourceIndex = prompt.indexOf("\nABC\n", untrustedSourceSectionIndex);

        assertTrue(matchingContractIndex >= 0);
        assertTrue(untrustedSourceSectionIndex > matchingContractIndex);
        assertTrue(sourceIndex > untrustedSourceSectionIndex);
    }
}
