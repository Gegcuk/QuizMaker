package uk.gegc.quizmaker.features.quiz.application.export.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.application.export.AnswerKeyBuilder;
import uk.gegc.quizmaker.features.quiz.application.export.ExportRenderer;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuestionExportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;
import uk.gegc.quizmaker.features.quiz.domain.model.export.AnswerKeyEntry;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportFile;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportPayload;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Professional PDF renderer for quiz exports.
 * Renders questions with proper formatting and answers on separate pages.
 */
@Component
@RequiredArgsConstructor
public class PdfPrintExportRenderer implements ExportRenderer {

    private final AnswerKeyBuilder answerKeyBuilder;

    private static final float MARGIN = 50f;
    private static final float TITLE_FONT_SIZE = 18f;
    private static final float HEADING_FONT_SIZE = 14f;
    private static final float NORMAL_FONT_SIZE = 11f;
    private static final float SMALL_FONT_SIZE = 9f;
    private static final float LINE_SPACING = 1.2f;
    private static final float QUESTION_SPACING = 20f;

    @Override
    public boolean supports(ExportFormat format) {
        return format == ExportFormat.PDF_PRINT;
    }

    @Override
    public ExportFile render(ExportPayload payload) {
        try (PDDocument document = new PDDocument()) {
            PDPageContext context = new PDPageContext(document);

            // Cover page if requested
            if (Boolean.TRUE.equals(payload.printOptions().includeCover())) {
                renderCoverPage(context, payload);
            }

            // Group and render questions
            List<QuizExportDto> quizzes = payload.quizzes();
            List<QuestionExportDto> allQuestions = new ArrayList<>();
            
            for (QuizExportDto quiz : quizzes) {
                if (quizzes.size() > 1 || Boolean.TRUE.equals(payload.printOptions().includeMetadata())) {
                    renderQuizHeader(context, quiz, payload);
                }
                allQuestions.addAll(quiz.questions());
            }

            // Render questions (grouped or sequential) and track render order
            List<QuestionExportDto> questionsInRenderOrder;
            if (Boolean.TRUE.equals(payload.printOptions().groupQuestionsByType())) {
                questionsInRenderOrder = renderQuestionsGroupedByType(context, allQuestions, payload);
            } else {
                questionsInRenderOrder = renderQuestionsSequential(context, allQuestions, payload);
            }

            // Answers on separate page - use same order as questions were rendered
            if (Boolean.TRUE.equals(payload.printOptions().answersOnSeparatePages())) {
                context.startNewPage();
                renderAnswerKey(context, questionsInRenderOrder, payload);
            }

            // Ensure the last page's content stream is closed before saving
            context.close();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            byte[] bytes = baos.toByteArray();

            String filename = payload.filenamePrefix() + ".pdf";
            return new ExportFile(
                    filename,
                    "application/pdf",
                    () -> new ByteArrayInputStream(bytes),
                    bytes.length
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to render PDF export", e);
        }
    }

    private void renderCoverPage(PDPageContext context, ExportPayload payload) throws IOException {
        context.startNewPage();
        context.y = PDRectangle.LETTER.getHeight() - 150;
        
        context.writeText("Quiz Export", PDType1Font.HELVETICA_BOLD, TITLE_FONT_SIZE * 1.5f);
        context.y -= 40;
        context.writeText("Generated: " + java.time.Instant.now().toString().substring(0, 19).replace('T', ' '),
                         PDType1Font.HELVETICA, NORMAL_FONT_SIZE);
        context.y -= 20;
        context.writeText("Total Quizzes: " + payload.quizzes().size(), 
                         PDType1Font.HELVETICA, NORMAL_FONT_SIZE);
        
        int totalQuestions = payload.quizzes().stream()
                .mapToInt(q -> q.questions().size())
                .sum();
        context.y -= 15;
        context.writeText("Total Questions: " + totalQuestions,
                         PDType1Font.HELVETICA, NORMAL_FONT_SIZE);
    }

    private void renderQuizHeader(PDPageContext context, QuizExportDto quiz, ExportPayload payload) throws IOException {
        context.ensureSpace(80);
        context.y -= 10;
        
        context.writeText(quiz.title(), PDType1Font.HELVETICA_BOLD, HEADING_FONT_SIZE);
        context.y -= 15;
        
        if (Boolean.TRUE.equals(payload.printOptions().includeMetadata())) {
            String meta = String.format("Difficulty: %s | Category: %s | Time: %d min",
                    quiz.difficulty(), quiz.category(), quiz.estimatedTime());
            context.writeText(meta, PDType1Font.HELVETICA, SMALL_FONT_SIZE);
            context.y -= 12;
            
            if (quiz.tags() != null && !quiz.tags().isEmpty()) {
                context.writeText("Tags: " + String.join(", ", quiz.tags()), 
                                 PDType1Font.HELVETICA, SMALL_FONT_SIZE);
                context.y -= 12;
            }
        }
        
        if (quiz.description() != null && !quiz.description().isBlank()) {
            context.writeWrappedText(quiz.description(), PDType1Font.HELVETICA, SMALL_FONT_SIZE, 500);
            context.y -= 5;
        }
        
        context.y -= 10;
    }

    private List<QuestionExportDto> renderQuestionsSequential(PDPageContext context, List<QuestionExportDto> questions, 
                                          ExportPayload payload) throws IOException {
        int questionNumber = 1;
        for (QuestionExportDto question : questions) {
            renderQuestion(context, question, questionNumber++, payload);
        }
        return questions; // Same order as input
    }

    private List<QuestionExportDto> renderQuestionsGroupedByType(PDPageContext context, List<QuestionExportDto> questions,
                                              ExportPayload payload) throws IOException {
        Map<String, List<QuestionExportDto>> grouped = questions.stream()
                .collect(Collectors.groupingBy(q -> q.type().name(), LinkedHashMap::new, Collectors.toList()));

        List<QuestionExportDto> renderOrder = new ArrayList<>();
        int questionNumber = 1;
        for (Map.Entry<String, List<QuestionExportDto>> entry : grouped.entrySet()) {
            context.ensureSpace(60);
            context.y -= 10;
            context.writeText(formatQuestionType(entry.getKey()) + " Questions", 
                             PDType1Font.HELVETICA_BOLD, HEADING_FONT_SIZE);
            context.y -= 15;

            for (QuestionExportDto question : entry.getValue()) {
                renderQuestion(context, question, questionNumber++, payload);
                renderOrder.add(question); // Track render order
            }
        }
        return renderOrder; // Return grouped order
    }

    private void renderQuestion(PDPageContext context, QuestionExportDto question, int number,
                               ExportPayload payload) throws IOException {
        // Estimate space needed for question (at least 60 points)
        float estimatedHeight = 60 + 
                               (Boolean.TRUE.equals(payload.printOptions().includeHints()) && question.hint() != null ? 30 : 0) +
                               (Boolean.TRUE.equals(payload.printOptions().includeExplanations()) && question.explanation() != null ? 30 : 0);
        
        context.ensureSpace(estimatedHeight);

        // Question number and text
        context.writeText(number + ". " + question.questionText(), 
                         PDType1Font.HELVETICA_BOLD, NORMAL_FONT_SIZE);
        context.y -= 12;

        // Type-specific content rendering
        renderQuestionContent(context, question);
        context.y -= 5;

        // Optional hint
        if (Boolean.TRUE.equals(payload.printOptions().includeHints()) && question.hint() != null && !question.hint().isBlank()) {
            context.writeText("Hint: " + question.hint(), PDType1Font.HELVETICA_OBLIQUE, SMALL_FONT_SIZE);
            context.y -= 12;
        }

        // Optional explanation
        if (Boolean.TRUE.equals(payload.printOptions().includeExplanations()) && question.explanation() != null && !question.explanation().isBlank()) {
            context.writeText("Explanation: " + question.explanation(), 
                             PDType1Font.HELVETICA_OBLIQUE, SMALL_FONT_SIZE);
            context.y -= 12;
        }

        context.y -= QUESTION_SPACING;
    }

    private void renderQuestionContent(PDPageContext context, QuestionExportDto question) throws IOException {
        JsonNode content = question.content();
        if (content == null || content.isNull()) {
            return;
        }

        switch (question.type()) {
            case MCQ_SINGLE, MCQ_MULTI -> renderMcqOptions(context, content);
            case TRUE_FALSE -> renderTrueFalse(context);
            case FILL_GAP -> renderFillGap(context, content);
            case ORDERING -> renderOrdering(context, content);
            case MATCHING -> renderMatching(context, content);
            case HOTSPOT -> renderHotspot(context, content);
            case COMPLIANCE -> renderCompliance(context, content);
            case OPEN -> {
                // Open questions just show space for answer
                context.writeText("Answer:", PDType1Font.HELVETICA, SMALL_FONT_SIZE);
                context.y -= 30; // Space for writing
            }
        }
    }

    private void renderMcqOptions(PDPageContext context, JsonNode content) throws IOException {
        if (content.has("options")) {
            for (JsonNode option : content.get("options")) {
                String optionText = option.has("text") ? option.get("text").asText() : "";
                context.writeText("   - " + optionText, PDType1Font.HELVETICA, NORMAL_FONT_SIZE);
                context.y -= 14;
            }
        }
    }

    private void renderTrueFalse(PDPageContext context) throws IOException {
        context.writeText("   - True", PDType1Font.HELVETICA, NORMAL_FONT_SIZE);
        context.y -= 14;
        context.writeText("   - False", PDType1Font.HELVETICA, NORMAL_FONT_SIZE);
        context.y -= 14;
    }

    private void renderFillGap(PDPageContext context, JsonNode content) throws IOException {
        context.writeText("   Fill in the blanks:", PDType1Font.HELVETICA_OBLIQUE, SMALL_FONT_SIZE);
        context.y -= 14;
        if (content.has("gaps")) {
            int gapNum = 1;
            for (int i = 0; i < content.get("gaps").size(); i++) {
                context.writeText("   Gap " + gapNum++ + ": _________________", 
                                 PDType1Font.HELVETICA, NORMAL_FONT_SIZE);
                context.y -= 14;
            }
        }
    }

    private void renderOrdering(PDPageContext context, JsonNode content) throws IOException {
        context.writeText("   Order the following items:", PDType1Font.HELVETICA_OBLIQUE, SMALL_FONT_SIZE);
        context.y -= 14;
        if (content.has("items")) {
            for (JsonNode item : content.get("items")) {
                String itemText = item.has("text") ? item.get("text").asText() : "";
                context.writeText("   - " + itemText, PDType1Font.HELVETICA, NORMAL_FONT_SIZE);
                context.y -= 14;
            }
        }
    }

    private void renderMatching(PDPageContext context, JsonNode content) throws IOException {
        context.writeText("   Match the items:", PDType1Font.HELVETICA_OBLIQUE, SMALL_FONT_SIZE);
        context.y -= 14;
        
        if (content.has("left") && content.has("right")) {
            context.writeText("   Left Column:", PDType1Font.HELVETICA_BOLD, SMALL_FONT_SIZE);
            context.y -= 12;
            for (JsonNode item : content.get("left")) {
                String text = item.has("text") ? item.get("text").asText() : "";
                context.writeText("   " + (item.has("id") ? item.get("id").asText() : "") + ". " + text, 
                                 PDType1Font.HELVETICA, NORMAL_FONT_SIZE);
                context.y -= 12;
            }
            context.y -= 5;
            context.writeText("   Right Column:", PDType1Font.HELVETICA_BOLD, SMALL_FONT_SIZE);
            context.y -= 12;
            for (JsonNode item : content.get("right")) {
                String text = item.has("text") ? item.get("text").asText() : "";
                context.writeText("   " + (item.has("id") ? item.get("id").asText() : "") + ". " + text,
                                 PDType1Font.HELVETICA, NORMAL_FONT_SIZE);
                context.y -= 12;
            }
        }
    }

    private void renderHotspot(PDPageContext context, JsonNode content) throws IOException {
        context.writeText("   Select the correct region on the image", 
                         PDType1Font.HELVETICA_OBLIQUE, SMALL_FONT_SIZE);
        context.y -= 14;
        if (content.has("imageUrl")) {
            context.writeText("   Image: " + content.get("imageUrl").asText(), 
                             PDType1Font.HELVETICA, SMALL_FONT_SIZE);
            context.y -= 14;
        }
    }

    private void renderCompliance(PDPageContext context, JsonNode content) throws IOException {
        context.writeText("   Mark each statement as Compliant or Non-compliant:", 
                         PDType1Font.HELVETICA_OBLIQUE, SMALL_FONT_SIZE);
        context.y -= 14;
        if (content.has("statements")) {
            for (JsonNode statement : content.get("statements")) {
                String text = statement.has("text") ? statement.get("text").asText() : "";
                context.writeText("   - " + text, PDType1Font.HELVETICA, NORMAL_FONT_SIZE);
                context.y -= 14;
            }
        }
    }

    private void renderAnswerKey(PDPageContext context, List<QuestionExportDto> questions,
                                 ExportPayload payload) throws IOException {
        context.ensureSpace(100);
        context.y -= 10;
        
        context.writeText("Answer Key", PDType1Font.HELVETICA_BOLD, TITLE_FONT_SIZE);
        context.y -= 25;

        List<AnswerKeyEntry> answers = answerKeyBuilder.build(questions);
        
        for (AnswerKeyEntry answer : answers) {
            context.ensureSpace(40);
            String answerText = String.format("%d. %s", 
                    answer.index(), 
                    formatAnswerForDisplay(answer));
            context.writeText(answerText, PDType1Font.HELVETICA, NORMAL_FONT_SIZE);
            context.y -= 15;
        }
    }

    private String formatAnswerForDisplay(AnswerKeyEntry answer) {
        JsonNode normalized = answer.normalizedAnswer();
        if (normalized == null || normalized.isNull()) {
            return "No answer";
        }
        
        // Format based on question type
        return switch (answer.type()) {
            case MCQ_SINGLE -> normalized.has("correctOptionId") ? 
                    "Option: " + normalized.get("correctOptionId").asText() : "N/A";
            case MCQ_MULTI -> normalized.has("correctOptionIds") ?
                    "Options: " + normalized.get("correctOptionIds").toString() : "N/A";
            case TRUE_FALSE -> normalized.has("answer") ?
                    normalized.get("answer").asBoolean() ? "True" : "False" : "N/A";
            case OPEN -> normalized.has("answer") && !normalized.get("answer").isNull() ?
                    normalized.get("answer").asText() : "Open answer (manual grading)";
            default -> normalized.toString();
        };
    }

    private String formatQuestionType(String type) {
        return switch (type) {
            case "MCQ_SINGLE" -> "Multiple Choice (Single)";
            case "MCQ_MULTI" -> "Multiple Choice (Multiple)";
            case "TRUE_FALSE" -> "True/False";
            case "FILL_GAP" -> "Fill in the Gap";
            case "ORDERING" -> "Ordering";
            case "MATCHING" -> "Matching";
            case "HOTSPOT" -> "Hotspot";
            case "COMPLIANCE" -> "Compliance";
            case "OPEN" -> "Open-Ended";
            default -> type;
        };
    }

    /**
     * Helper class to manage PDF page context and prevent question splits
     */
    private static class PDPageContext {
        private final PDDocument document;
        private PDPage currentPage;
        private PDPageContentStream contentStream;
        private float y;
        private final float pageHeight = PDRectangle.LETTER.getHeight();

        public PDPageContext(PDDocument document) {
            this.document = document;
        }

        public void startNewPage() throws IOException {
            if (contentStream != null) {
                contentStream.close();
            }
            currentPage = new PDPage(PDRectangle.LETTER);
            document.addPage(currentPage);
            contentStream = new PDPageContentStream(document, currentPage);
            y = pageHeight - MARGIN;
        }

        public void ensureSpace(float requiredSpace) throws IOException {
            if (currentPage == null || y < MARGIN + requiredSpace) {
                startNewPage();
            }
        }

        public void writeText(String text, PDFont font, float fontSize) throws IOException {
            if (currentPage == null) {
                startNewPage();
            }
            contentStream.beginText();
            contentStream.setFont(font, fontSize);
            contentStream.newLineAtOffset(MARGIN, y);
            contentStream.showText(text);
            contentStream.endText();
            y -= fontSize * LINE_SPACING;
        }

        public void writeWrappedText(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
            if (text == null || text.isBlank()) return;
            
            String[] words = text.split("\\s+");
            StringBuilder line = new StringBuilder();
            
            for (String word : words) {
                String testLine = line.length() == 0 ? word : line + " " + word;
                try {
                    float width = font.getStringWidth(testLine) / 1000 * fontSize;
                    if (width > maxWidth && line.length() > 0) {
                        writeText(line.toString(), font, fontSize);
                        line = new StringBuilder(word);
                    } else {
                        line = new StringBuilder(testLine);
                    }
                } catch (IOException e) {
                    line.append(" ").append(word);
                }
            }
            
            if (line.length() > 0) {
                writeText(line.toString(), font, fontSize);
            }
        }

        public void close() throws IOException {
            if (contentStream != null) {
                contentStream.close();
            }
        }
    }
}
