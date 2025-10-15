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
    private static final float PAGE_WIDTH = PDRectangle.LETTER.getWidth();
    private static final float MAX_TEXT_WIDTH = PAGE_WIDTH - (2 * MARGIN); // Available width for text
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
            context.writeWrappedText(quiz.description(), PDType1Font.HELVETICA, SMALL_FONT_SIZE);
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

    /**
     * Estimate the height needed to render a complete question.
     * This helps prevent questions from being split across pages.
     */
    private float estimateQuestionHeight(QuestionExportDto question, ExportPayload payload) throws IOException {
        float height = 0;
        
        // Question text (wrapped, average 2 lines)
        height += NORMAL_FONT_SIZE * LINE_SPACING * 2;
        height += 12; // Space after question
        
        // Content based on question type
        JsonNode content = question.content();
        if (content != null && !content.isNull()) {
            switch (question.type()) {
                case MCQ_SINGLE, MCQ_MULTI -> {
                    if (content.has("options")) {
                        int optionCount = content.get("options").size();
                        height += optionCount * 14; // Each option takes ~14 points
                    }
                }
                case TRUE_FALSE -> height += 28; // 2 options
                case FILL_GAP -> {
                    if (content.has("gaps")) {
                        int gapCount = content.get("gaps").size();
                        height += gapCount * 14;
                    }
                }
                case ORDERING -> {
                    if (content.has("items")) {
                        int itemCount = content.get("items").size();
                        height += itemCount * 14;
                    }
                }
                case MATCHING -> {
                    if (content.has("left") && content.has("right")) {
                        int maxItems = Math.max(
                            content.get("left").size(),
                            content.get("right").size()
                        );
                        height += 12; // Column headers
                        height += maxItems * 16; // Items in two columns (slightly more space)
                    }
                }
                case COMPLIANCE -> {
                    if (content.has("statements")) {
                        int stmtCount = content.get("statements").size();
                        height += stmtCount * 16; // Statements with checkboxes
                    }
                }
                case HOTSPOT -> height += 80; // Image reference space
                case OPEN -> height += 30; // Answer space
            }
        }
        
        height += 5; // Space after content
        
        // Optional hint
        if (Boolean.TRUE.equals(payload.printOptions().includeHints()) && 
            question.hint() != null && !question.hint().isBlank()) {
            height += SMALL_FONT_SIZE * LINE_SPACING * 2; // Average 2 lines
            height += 12;
        }
        
        // Optional explanation
        if (Boolean.TRUE.equals(payload.printOptions().includeExplanations()) && 
            question.explanation() != null && !question.explanation().isBlank()) {
            height += SMALL_FONT_SIZE * LINE_SPACING * 3; // Average 3 lines
            height += 12;
        }
        
        height += QUESTION_SPACING; // Space between questions
        
        return height;
    }

    private void renderQuestion(PDPageContext context, QuestionExportDto question, int number,
                               ExportPayload payload) throws IOException {
        // Calculate accurate space needed for the entire question
        float estimatedHeight = estimateQuestionHeight(question, payload);
        
        // If not enough space, start a new page
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
            int optionIdx = 0;
            for (JsonNode option : content.get("options")) {
                String optionText = option.has("text") ? option.get("text").asText() : "";
                char label = (char) ('A' + optionIdx);
                context.writeText("   " + label + ". " + optionText, PDType1Font.HELVETICA, NORMAL_FONT_SIZE);
                context.y -= 14;
                optionIdx++;
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
        if (content.has("items")) {
            int itemIdx = 0;
            for (JsonNode item : content.get("items")) {
                String itemText = item.has("text") ? item.get("text").asText() : "";
                char label = (char) ('A' + itemIdx);
                context.writeText("   " + label + ". " + itemText, PDType1Font.HELVETICA, NORMAL_FONT_SIZE);
                context.y -= 14;
                itemIdx++;
            }
        }
    }

    private void renderMatching(PDPageContext context, JsonNode content) throws IOException {
        if (content.has("left") && content.has("right")) {
            // Two-column layout: Column 1 (numbers) and Column 2 (letters)
            float leftColumnX = MARGIN + 20;
            float rightColumnX = MARGIN + (MAX_TEXT_WIDTH / 2) + 20;
            float columnWidth = (MAX_TEXT_WIDTH / 2) - 40;
            
            // Headers
            context.writeTwoColumnText("Column 1", "Column 2", 
                                      leftColumnX, rightColumnX,
                                      PDType1Font.HELVETICA_BOLD, SMALL_FONT_SIZE);
            context.y -= 12;
            
            // Get items as lists
            List<JsonNode> leftItems = new ArrayList<>();
            List<JsonNode> rightItems = new ArrayList<>();
            content.get("left").forEach(leftItems::add);
            content.get("right").forEach(rightItems::add);
            
            // Render items in parallel (numbers on left, letters on right)
            int maxItems = Math.max(leftItems.size(), rightItems.size());
            for (int i = 0; i < maxItems; i++) {
                String leftText = "";
                String rightText = "";
                
                if (i < leftItems.size()) {
                    String text = leftItems.get(i).has("text") ? leftItems.get(i).get("text").asText() : "";
                    leftText = (i + 1) + ". " + text;
                }
                
                if (i < rightItems.size()) {
                    String text = rightItems.get(i).has("text") ? rightItems.get(i).get("text").asText() : "";
                    char label = (char) ('A' + i);
                    rightText = label + ". " + text;
                }
                
                context.writeTwoColumnWrappedText(leftText, rightText,
                                                 leftColumnX, rightColumnX,
                                                 columnWidth,
                                                 PDType1Font.HELVETICA, NORMAL_FONT_SIZE);
                context.y -= 3;
            }
        }
    }

    private void renderHotspot(PDPageContext context, JsonNode content) throws IOException {
        if (content.has("imageUrl")) {
            context.writeText("   Image: " + content.get("imageUrl").asText(), 
                             PDType1Font.HELVETICA, SMALL_FONT_SIZE);
            context.y -= 14;
        }
    }

    private void renderCompliance(PDPageContext context, JsonNode content) throws IOException {
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
        
        // Build question lookup map
        Map<java.util.UUID, QuestionExportDto> questionMap = questions.stream()
            .collect(java.util.stream.Collectors.toMap(QuestionExportDto::id, q -> q));
        
        for (AnswerKeyEntry answer : answers) {
            context.ensureSpace(40);
            QuestionExportDto question = questionMap.get(answer.questionId());
            String answerText = String.format("%d. %s", 
                    answer.index(), 
                    formatAnswerForDisplay(answer, question));
            context.writeText(answerText, PDType1Font.HELVETICA, NORMAL_FONT_SIZE);
            context.y -= 15;
        }
    }

    private String formatAnswerForDisplay(AnswerKeyEntry answer, QuestionExportDto question) {
        JsonNode normalized = answer.normalizedAnswer();
        if (normalized == null || normalized.isNull()) {
            return "No answer";
        }
        
        JsonNode originalContent = question != null ? question.content() : null;
        
        // Format based on question type
        return switch (answer.type()) {
            case MCQ_SINGLE -> formatMcqSingleAnswer(normalized, originalContent);
            case MCQ_MULTI -> formatMcqMultiAnswer(normalized, originalContent);
            case TRUE_FALSE -> normalized.has("answer") ?
                    (normalized.get("answer").asBoolean() ? "True" : "False") : "N/A";
            case OPEN -> normalized.has("answer") && !normalized.get("answer").isNull() ?
                    normalized.get("answer").asText() : "Open answer (manual grading)";
            case MATCHING -> formatMatchingAnswer(normalized, originalContent);
            case ORDERING -> formatOrderingAnswer(normalized, originalContent);
            case COMPLIANCE -> formatComplianceAnswer(normalized, originalContent);
            case FILL_GAP -> formatFillGapAnswer(normalized);
            default -> normalized.toString();
        };
    }
    
    private String formatMcqSingleAnswer(JsonNode normalized, JsonNode originalContent) {
        if (!normalized.has("correctOptionId")) {
            return "N/A";
        }
        
        String correctId = normalized.get("correctOptionId").asText();
        
        // Find the option position and convert to letter
        if (originalContent != null && originalContent.has("options")) {
            int optionIdx = 0;
            for (JsonNode option : originalContent.get("options")) {
                String optionId = option.has("id") ? option.get("id").asText() : "";
                if (optionId.equals(correctId)) {
                    char label = (char) ('A' + optionIdx);
                    return String.valueOf(label);
                }
                optionIdx++;
            }
        }
        
        return "Option: " + correctId;
    }
    
    private String formatMcqMultiAnswer(JsonNode normalized, JsonNode originalContent) {
        if (!normalized.has("correctOptionIds")) {
            return "N/A";
        }
        
        JsonNode correctIds = normalized.get("correctOptionIds");
        
        // Build set of correct IDs
        java.util.Set<String> correctIdSet = new java.util.HashSet<>();
        for (JsonNode id : correctIds) {
            correctIdSet.add(id.asText());
        }
        
        // Find matching option positions and convert to letters
        List<Character> correctLetters = new ArrayList<>();
        if (originalContent != null && originalContent.has("options")) {
            int optionIdx = 0;
            for (JsonNode option : originalContent.get("options")) {
                String optionId = option.has("id") ? option.get("id").asText() : "";
                if (correctIdSet.contains(optionId)) {
                    char label = (char) ('A' + optionIdx);
                    correctLetters.add(label);
                }
                optionIdx++;
            }
        }
        
        if (!correctLetters.isEmpty()) {
            return correctLetters.stream()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(", "));
        }
        return "Options: " + correctIds.toString();
    }
    
    private String formatMatchingAnswer(JsonNode normalized, JsonNode originalContent) {
        if (!normalized.has("pairs")) {
            return "N/A";
        }
        
        // Build ID-to-position lookup maps (for number and letter conversion)
        Map<Integer, Integer> leftIdToPosition = new LinkedHashMap<>();
        Map<Integer, Integer> rightIdToPosition = new LinkedHashMap<>();
        
        if (originalContent != null) {
            if (originalContent.has("left")) {
                int position = 1;
                for (JsonNode item : originalContent.get("left")) {
                    int id = item.has("id") ? item.get("id").asInt() : 0;
                    leftIdToPosition.put(id, position++);
                }
            }
            if (originalContent.has("right")) {
                int position = 0;
                for (JsonNode item : originalContent.get("right")) {
                    int id = item.has("id") ? item.get("id").asInt() : 0;
                    rightIdToPosition.put(id, position++);
                }
            }
        }
        
        // Format pairs as "1 -> A, 2 -> B" etc.
        List<String> pairTexts = new ArrayList<>();
        JsonNode pairs = normalized.get("pairs");
        for (JsonNode pair : pairs) {
            int leftId = pair.has("leftId") ? pair.get("leftId").asInt() : 0;
            int rightId = pair.has("rightId") ? pair.get("rightId").asInt() : 0;
            
            Integer leftNum = leftIdToPosition.get(leftId);
            Integer rightIdx = rightIdToPosition.get(rightId);
            
            if (leftNum != null && rightIdx != null) {
                char rightLetter = (char) ('A' + rightIdx);
                pairTexts.add(leftNum + " -> " + rightLetter);
            }
        }
        return String.join(", ", pairTexts);
    }
    
    private String formatOrderingAnswer(JsonNode normalized, JsonNode originalContent) {
        if (!normalized.has("order")) {
            return "N/A";
        }
        
        // Build ID-to-letter position map (A, B, C, D based on display order)
        Map<Integer, Integer> itemIdToPosition = new LinkedHashMap<>();
        if (originalContent != null && originalContent.has("items")) {
            int position = 0;
            for (JsonNode item : originalContent.get("items")) {
                int id = item.has("id") ? item.get("id").asInt() : 0;
                itemIdToPosition.put(id, position++);
            }
        }
        
        // Convert correct order IDs to letters
        List<String> orderedLetters = new ArrayList<>();
        JsonNode order = normalized.get("order");
        for (JsonNode item : order) {
            int id = item.asInt();
            Integer position = itemIdToPosition.get(id);
            if (position != null) {
                char letter = (char) ('A' + position);
                orderedLetters.add(String.valueOf(letter));
            }
        }
        return String.join(" -> ", orderedLetters);
    }
    
    private String formatComplianceAnswer(JsonNode normalized, JsonNode originalContent) {
        if (!normalized.has("compliantIds")) {
            return "N/A";
        }
        
        // Build ID-to-position lookup map (for statement numbers)
        Map<Integer, Integer> statementIdToPosition = new LinkedHashMap<>();
        if (originalContent != null && originalContent.has("statements")) {
            int position = 1;
            for (JsonNode stmt : originalContent.get("statements")) {
                int id = stmt.has("id") ? stmt.get("id").asInt() : 0;
                statementIdToPosition.put(id, position++);
            }
        }
        
        List<String> compliantPositions = new ArrayList<>();
        JsonNode ids = normalized.get("compliantIds");
        for (JsonNode id : ids) {
            int stmtId = id.asInt();
            Integer position = statementIdToPosition.get(stmtId);
            if (position != null) {
                compliantPositions.add(String.valueOf(position));
            }
        }
        return "Compliant: " + String.join(", ", compliantPositions);
    }
    
    private String formatFillGapAnswer(JsonNode normalized) {
        if (!normalized.has("answers")) {
            return "N/A";
        }
        
        List<String> gapAnswers = new ArrayList<>();
        JsonNode answers = normalized.get("answers");
        int idx = 0;
        for (JsonNode answerObj : answers) {
            String text = answerObj.has("text") ? answerObj.get("text").asText() : "";
            gapAnswers.add((idx + 1) + ". " + text);
            idx++;
        }
        return String.join(", ", gapAnswers);
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

        /**
         * Write text with automatic wrapping to fit page width
         */
        public void writeText(String text, PDFont font, float fontSize) throws IOException {
            writeWrappedText(text, font, fontSize);
        }

        /**
         * Write text with word wrapping to fit within page margins
         */
        public void writeWrappedText(String text, PDFont font, float fontSize) throws IOException {
            if (text == null || text.isBlank()) return;
            if (currentPage == null) {
                startNewPage();
            }
            
            String[] words = text.split("\\s+");
            StringBuilder line = new StringBuilder();
            
            for (String word : words) {
                String testLine = line.length() == 0 ? word : line + " " + word;
                try {
                    float width = font.getStringWidth(testLine) / 1000 * fontSize;
                    if (width > MAX_TEXT_WIDTH && line.length() > 0) {
                        // Write current line
                        writeSingleLine(line.toString(), font, fontSize);
                        line = new StringBuilder(word);
                    } else {
                        line = new StringBuilder(testLine);
                    }
                } catch (IOException e) {
                    // If we can't calculate width, just append
                    line.append(" ").append(word);
                }
            }
            
            if (line.length() > 0) {
                writeSingleLine(line.toString(), font, fontSize);
            }
        }
        
        /**
         * Write a single line of text without wrapping (internal use only)
         */
        private void writeSingleLine(String text, PDFont font, float fontSize) throws IOException {
            if (currentPage == null) {
                startNewPage();
            }
            ensureSpace(fontSize * LINE_SPACING);
            contentStream.beginText();
            contentStream.setFont(font, fontSize);
            contentStream.newLineAtOffset(MARGIN, y);
            contentStream.showText(text);
            contentStream.endText();
            y -= fontSize * LINE_SPACING;
        }
        
        /**
         * Write text in two columns (simple, single-line version)
         */
        public void writeTwoColumnText(String leftText, String rightText,
                                      float leftX, float rightX,
                                      PDFont font, float fontSize) throws IOException {
            if (currentPage == null) {
                startNewPage();
            }
            ensureSpace(fontSize * LINE_SPACING);
            
            // Left column
            contentStream.beginText();
            contentStream.setFont(font, fontSize);
            contentStream.newLineAtOffset(leftX, y);
            contentStream.showText(leftText);
            contentStream.endText();
            
            // Right column
            contentStream.beginText();
            contentStream.setFont(font, fontSize);
            contentStream.newLineAtOffset(rightX, y);
            contentStream.showText(rightText);
            contentStream.endText();
            
            y -= fontSize * LINE_SPACING;
        }
        
        /**
         * Write text in two columns with word wrapping for each column
         */
        public void writeTwoColumnWrappedText(String leftText, String rightText,
                                             float leftX, float rightX,
                                             float columnWidth,
                                             PDFont font, float fontSize) throws IOException {
            if (leftText == null) leftText = "";
            if (rightText == null) rightText = "";
            
            // Wrap both texts to their column widths
            List<String> leftLines = wrapTextToLines(leftText, font, fontSize, columnWidth);
            List<String> rightLines = wrapTextToLines(rightText, font, fontSize, columnWidth);
            
            // Render line by line, using the max number of lines from either column
            int maxLines = Math.max(leftLines.size(), rightLines.size());
            for (int i = 0; i < maxLines; i++) {
                if (currentPage == null) {
                    startNewPage();
                }
                ensureSpace(fontSize * LINE_SPACING);
                
                // Left column line
                if (i < leftLines.size()) {
                    contentStream.beginText();
                    contentStream.setFont(font, fontSize);
                    contentStream.newLineAtOffset(leftX, y);
                    contentStream.showText(leftLines.get(i));
                    contentStream.endText();
                }
                
                // Right column line
                if (i < rightLines.size()) {
                    contentStream.beginText();
                    contentStream.setFont(font, fontSize);
                    contentStream.newLineAtOffset(rightX, y);
                    contentStream.showText(rightLines.get(i));
                    contentStream.endText();
                }
                
                y -= fontSize * LINE_SPACING;
            }
        }
        
        /**
         * Helper method to wrap text into lines that fit within a given width
         */
        private List<String> wrapTextToLines(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            if (text == null || text.isBlank()) {
                return lines;
            }
            
            String[] words = text.split("\\s+");
            StringBuilder line = new StringBuilder();
            
            for (String word : words) {
                String testLine = line.length() == 0 ? word : line + " " + word;
                try {
                    float width = font.getStringWidth(testLine) / 1000 * fontSize;
                    if (width > maxWidth && line.length() > 0) {
                        lines.add(line.toString());
                        line = new StringBuilder(word);
                    } else {
                        line = new StringBuilder(testLine);
                    }
                } catch (IOException e) {
                    line.append(" ").append(word);
                }
            }
            
            if (line.length() > 0) {
                lines.add(line.toString());
            }
            
            return lines;
        }

        public void close() throws IOException {
            if (contentStream != null) {
                contentStream.close();
            }
        }
    }
}
