package uk.gegc.quizmaker.features.document.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.config.TestAiConfig;
import uk.gegc.quizmaker.features.document.api.dto.DocumentOutlineDto;
import uk.gegc.quizmaker.features.document.api.dto.OutlineNodeDto;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test-mysql")
@Import(TestAiConfig.class)
class OutlineExtractorServiceIntegrationTest {

    @Autowired
    private OutlineExtractorService outlineExtractorService;

    @Test
    void extractOutline_WithSimpleDocument_Success() {
        // Given
        String documentContent = """
            Chapter 1: Introduction to Machine Learning
            
            Machine learning is a subset of artificial intelligence that enables computers to learn and make decisions without being explicitly programmed.
            
            Section 1.1: What is Machine Learning?
            
            Machine learning algorithms build mathematical models based on sample data, known as training data, to make predictions or decisions without being explicitly programmed to perform the task.
            
            Section 1.2: Types of Machine Learning
            
            There are three main types of machine learning: supervised learning, unsupervised learning, and reinforcement learning.
            
            Chapter 2: Supervised Learning
            
            Supervised learning is a type of machine learning where the algorithm learns from labeled training data.
            
            Section 2.1: Classification
            
            Classification is a supervised learning task where the goal is to predict a categorical label.
            
            Section 2.2: Regression
            
            Regression is a supervised learning task where the goal is to predict a continuous value.
            """;

        // When
        DocumentOutlineDto result = outlineExtractorService.extractOutline(documentContent);

        // Then
        assertNotNull(result);
        assertNotNull(result.nodes());
        assertFalse(result.nodes().isEmpty());
        
        // Verify we have at least one chapter
        boolean hasChapter = result.nodes().stream()
                .anyMatch(node -> "CHAPTER".equals(node.type()));
        assertTrue(hasChapter, "Should have at least one chapter");
        
        // Verify structure
        for (OutlineNodeDto node : result.nodes()) {
            assertNotNull(node.type());
            assertNotNull(node.title());
            assertNotNull(node.startAnchor());
            assertNotNull(node.endAnchor());
            assertNotNull(node.children());
            
            // Verify node type is valid
            assertDoesNotThrow(() -> node.getNodeType());
            assertTrue(node.getLevel() >= 0);
        }
    }

    @Test
    void extractOutline_WithComplexHierarchy_Success() {
        // Given
        String documentContent = """
            Part I: Fundamentals
            
            Chapter 1: Basic Concepts
            This chapter covers the fundamental concepts.
            
            Section 1.1: Core Principles
            The core principles form the foundation.
            
            Subsection 1.1.1: Principle One
            The first principle is essential.
            
            Subsection 1.1.2: Principle Two
            The second principle builds on the first.
            
            Section 1.2: Advanced Topics
            Advanced topics extend the basics.
            
            Chapter 2: Applications
            This chapter shows practical applications.
            
            Part II: Advanced Topics
            
            Chapter 3: Advanced Techniques
            Advanced techniques for experts.
            """;

        // When
        DocumentOutlineDto result = outlineExtractorService.extractOutline(documentContent);

        // Then
        assertNotNull(result);
        assertNotNull(result.nodes());
        assertFalse(result.nodes().isEmpty());
        
        // Verify we have parts
        boolean hasPart = result.nodes().stream()
                .anyMatch(node -> "PART".equals(node.type()));
        assertTrue(hasPart, "Should have at least one part");
        
        // Verify hierarchy depth
        int maxDepth = getMaxDepth(result.nodes(), 0);
        assertTrue(maxDepth <= 4, "Hierarchy should not exceed 4 levels");
    }

    @Test
    void extractOutline_WithLongDocument_Success() {
        // Given - Create a longer document to test performance
        StringBuilder longDocument = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            longDocument.append("Chapter ").append(i).append(": Chapter Title ").append(i).append("\n\n");
            longDocument.append("This is the content for chapter ").append(i).append(". ");
            longDocument.append("It contains multiple sentences to provide enough content for the AI to analyze. ");
            longDocument.append("The content should be substantial enough to allow proper outline extraction.\n\n");
            
            for (int j = 1; j <= 3; j++) {
                longDocument.append("Section ").append(i).append(".").append(j).append(": Section Title ").append(j).append("\n\n");
                longDocument.append("This section contains detailed information about topic ").append(j).append(". ");
                longDocument.append("It provides context and examples to illustrate the concepts.\n\n");
            }
        }

        // When
        DocumentOutlineDto result = outlineExtractorService.extractOutline(longDocument.toString());

        // Then
        assertNotNull(result);
        assertNotNull(result.nodes());
        assertFalse(result.nodes().isEmpty());
        
        // Verify we have multiple chapters
        long chapterCount = result.nodes().stream()
                .filter(node -> "CHAPTER".equals(node.type()))
                .count();
        assertTrue(chapterCount > 0, "Should have at least one chapter");
    }

    private int getMaxDepth(List<OutlineNodeDto> nodes, int currentDepth) {
        if (nodes == null || nodes.isEmpty()) {
            return currentDepth;
        }
        
        int maxDepth = currentDepth;
        for (OutlineNodeDto node : nodes) {
            int childDepth = getMaxDepth(node.children(), currentDepth + 1);
            maxDepth = Math.max(maxDepth, childDepth);
        }
        
        return maxDepth;
    }
}
