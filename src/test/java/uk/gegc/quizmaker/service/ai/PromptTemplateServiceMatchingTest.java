package uk.gegc.quizmaker.service.ai;

import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
class PromptTemplateServiceMatchingTest {

    @Mock
    private ResourceLoader resourceLoader;

    @Mock private Resource systemPromptResource;
    @Mock private Resource contextTemplateResource;
    @Mock private Resource matchingTemplateResource;

    @InjectMocks
    private PromptTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "templateCache", new ConcurrentHashMap<>());
    }

    @Test
    void buildsMatchingPrompt_usesMatchingTemplate() throws IOException {
        when(resourceLoader.getResource("classpath:prompts/base/system-prompt.txt"))
                .thenReturn(systemPromptResource);
        when(systemPromptResource.getInputStream())
                .thenReturn(new ByteArrayInputStream("SYS".getBytes()));

        when(resourceLoader.getResource("classpath:prompts/base/context-template.txt"))
                .thenReturn(contextTemplateResource);
        when(contextTemplateResource.getInputStream())
                .thenReturn(new ByteArrayInputStream("CTX {content}".getBytes()));

        when(resourceLoader.getResource("classpath:prompts/question-types/matching.txt"))
                .thenReturn(matchingTemplateResource);
        when(matchingTemplateResource.getInputStream())
                .thenReturn(new ByteArrayInputStream("Generate {questionType} questions with {difficulty} difficulty.".getBytes()));

        String prompt = service.buildPromptForChunk("ABC", QuestionType.MATCHING, 2, Difficulty.EASY);
        assertNotNull(prompt);
        assertTrue(prompt.contains("MATCHING"));
        assertTrue(prompt.contains("EASY"));
        assertTrue(prompt.contains("ABC"));
    }
}


