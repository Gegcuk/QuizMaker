package uk.gegc.quizmaker.features.quiz.application.export.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
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
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class HtmlPrintExportRenderer implements ExportRenderer {

    private final AnswerKeyBuilder answerKeyBuilder;

    @Override
    public boolean supports(ExportFormat format) {
        return format == ExportFormat.HTML_PRINT;
    }

    @Override
    public ExportFile render(ExportPayload payload) {
        String html = buildHtml(payload);
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        String filename = payload.filenamePrefix() + ".html";

        return new ExportFile(
                filename,
                "text/html; charset=utf-8",
                () -> new ByteArrayInputStream(bytes),
                bytes.length
        );
    }

    private String buildHtml(ExportPayload payload) {
        StringBuilder sb = new StringBuilder();
        // Use quiz title for single quiz, or generic title for multiple
        String pageTitle = payload.quizzes().size() == 1 
            ? escape(payload.quizzes().get(0).title())
            : "Multiple Quiz Export";
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><title>").append(pageTitle).append("</title>");
        sb.append("<style>");
        sb.append("body{font-family:sans-serif;margin:24px;line-height:1.6;padding-bottom:40px;} ");
        sb.append("h1{margin-bottom:0;} ");
        sb.append("h2{margin-top:32px;border-bottom:2px solid #333;padding-bottom:8px;} ");
        sb.append("h3{margin-top:24px;color:#555;} ");
        sb.append(".meta{color:#555;font-size:14px;} ");
        sb.append(".page-break{page-break-before:always;} ");
        sb.append(".question{margin:20px 0;padding:12px;background:#f9f9f9;border-left:3px solid #333;page-break-inside:avoid;} ");
        sb.append(".question-header{font-weight:bold;font-size:16px;margin-bottom:8px;} ");
        sb.append(".question-content{margin:8px 0;} ");
        sb.append(".hint{margin-top:12px;padding:12px;background:#fffbea;border-left:4px solid #fbbf24;border-radius:4px;color:#92400e;font-size:14px;} ");
        sb.append(".hint strong{color:#b45309;} ");
        sb.append(".explanation{margin-top:12px;padding:12px;background:#dbeafe;border-left:4px solid #3b82f6;border-radius:4px;color:#1e3a8a;font-size:14px;} ");
        sb.append(".explanation strong{color:#1e40af;} ");
        sb.append(".answer-key{margin-top:32px;} ");
        sb.append(".answer-key h2{margin-top:0;} ");
        sb.append(".answer-entry{margin:8px 0;padding:8px;background:#f9f9f9;} ");
        sb.append(".tag{display:inline-block;margin-right:6px;color:#666;font-size:12px;} ");
        sb.append(".category{color:#666;font-size:12px;} ");
        sb.append(".cover{margin-bottom:24px;border-bottom:1px solid #ddd;padding-bottom:12px;} ");
        sb.append(".type-section{margin-top:32px;} ");
        sb.append(".matching-columns{display:grid;grid-template-columns:1fr 1fr;gap:40px;margin-top:8px;} ");
        sb.append(".matching-col{background:transparent;padding:0;border:none;} ");
        sb.append(".matching-col h4{margin:0 0 8px 0;color:#374151;font-size:14px;font-weight:600;} ");
        sb.append(".matching-col ul{list-style:none;padding-left:0;margin:0;} ");
        sb.append(".matching-col li{padding:2px 0;} ");
        sb.append(".footer{display:none;} ");
        sb.append("@media print{");
        sb.append("@page{margin:0.5in;@top-left{content:none;}@top-center{content:none;}@top-right{content:none;}@bottom-center{content:'Version: ").append(escape(payload.versionCode())).append("';font-size:10px;color:#666;}} ");
        sb.append(".footer{display:none;} ");
        sb.append("} ");
        sb.append("</style>");
        sb.append("</head><body>");

        // Render cover page if requested
        boolean hasCover = Boolean.TRUE.equals(payload.printOptions().includeCover());
        if (hasCover) {
            renderCoverPage(sb, payload);
            // Start questions on a new page after cover
            sb.append("<div class=\"page-break\"></div>");
        }
        
        // Collect all questions from all quizzes
        List<QuestionExportDto> allQuestions = new ArrayList<>();
        for (QuizExportDto quiz : payload.quizzes()) {
            // Render quiz header for multiple quizzes, OR for single quiz without cover
            if (payload.quizzes().size() > 1 || !hasCover) {
                renderQuizHeader(sb, quiz, payload);
            }
            
            allQuestions.addAll(quiz.questions());
        }

        // Determine rendering order (for answer key consistency)
        List<QuestionExportDto> questionsInRenderOrder;
        if (Boolean.TRUE.equals(payload.printOptions().groupQuestionsByType())) {
            questionsInRenderOrder = renderQuestionsGroupedByType(sb, allQuestions, payload);
        } else {
            questionsInRenderOrder = renderQuestionsSequential(sb, allQuestions, payload);
        }

        // Answer key - use same order as questions were rendered
        List<AnswerKeyEntry> answerKey = answerKeyBuilder.build(questionsInRenderOrder);

        if (Boolean.TRUE.equals(payload.printOptions().answersOnSeparatePages())) {
            sb.append("<div class=\"page-break\"></div>");
        }

        sb.append("<section class=\"answer-key\" id=\"answer-key\">");
        sb.append("<h2>Answer Key</h2>");
        int akIdx = 1;
        
        // Build question lookup map for text resolution
        Map<UUID, QuestionExportDto> questionMap = allQuestions.stream()
            .collect(Collectors.toMap(QuestionExportDto::id, q -> q));
        
        for (AnswerKeyEntry entry : answerKey) {
            QuestionExportDto question = questionMap.get(entry.questionId());
            sb.append("<div class=\"answer-entry\"><strong>").append(akIdx++).append(".</strong> ");
            formatAnswerForDisplayHtml(sb, entry, question);
            sb.append("</div>");
        }
        sb.append("</section>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private void renderCoverPage(StringBuilder sb, ExportPayload payload) {
        sb.append("<div class=\"cover\">");
        
        // Use quiz title for single quiz, generic title for multiple
        if (payload.quizzes().size() == 1) {
            QuizExportDto quiz = payload.quizzes().get(0);
            sb.append("<h1>").append(escape(quiz.title())).append("</h1>");
            
            // Include metadata if requested
            if (Boolean.TRUE.equals(payload.printOptions().includeMetadata())) {
                sb.append("<p class=\"meta\">");
                sb.append("Difficulty: ").append(quiz.difficulty());
                if (quiz.category() != null) {
                    sb.append(" | Category: ").append(escape(quiz.category()));
                }
                if (quiz.estimatedTime() != null) {
                    sb.append(" | Time: ").append(quiz.estimatedTime()).append(" min");
                }
                sb.append("</p>");
                
                if (quiz.tags() != null && !quiz.tags().isEmpty()) {
                    sb.append("<p class=\"meta\">Tags: ");
                    sb.append(quiz.tags().stream().map(this::escape).collect(java.util.stream.Collectors.joining(", ")));
                    sb.append("</p>");
                }
            }
            
            if (quiz.description() != null && !quiz.description().isBlank()) {
                sb.append("<p>").append(escape(quiz.description())).append("</p>");
            }
        } else {
            sb.append("<h1>Multiple Quiz Export</h1>");
            sb.append("<p class=\"meta\">Total Quizzes: ").append(payload.quizzes().size()).append("</p>");
            int totalQuestions = payload.quizzes().stream().mapToInt(q -> q.questions().size()).sum();
            sb.append("<p class=\"meta\">Total Questions: ").append(totalQuestions).append("</p>");
        }
        
        sb.append("<p style=\"margin-top:24px;\"><strong>Content:</strong> <a href=\"#answer-key\">Jump to Answer Key</a></p>");
        sb.append("</div>");
    }
    
    private void renderQuizHeader(StringBuilder sb, QuizExportDto quiz, ExportPayload payload) {
        sb.append("<section class=\"quiz\">");
        sb.append("<h2>").append(escape(quiz.title())).append("</h2>");
        
        if (quiz.description() != null && !quiz.description().isBlank()) {
            sb.append("<p>").append(escape(quiz.description())).append("</p>");
        }
        sb.append("</section>");
    }

    private List<QuestionExportDto> renderQuestionsSequential(StringBuilder sb, List<QuestionExportDto> questions, ExportPayload payload) {
        int questionNumber = 1;
        for (QuestionExportDto question : questions) {
            renderQuestion(sb, question, questionNumber++, payload);
        }
        return questions; // Same order as input
    }

    private List<QuestionExportDto> renderQuestionsGroupedByType(StringBuilder sb, List<QuestionExportDto> questions, ExportPayload payload) {
        Map<String, List<QuestionExportDto>> grouped = questions.stream()
                .collect(Collectors.groupingBy(
                    q -> q.type().name(),
                    LinkedHashMap::new,
                    Collectors.toList()
                ));

        List<QuestionExportDto> renderOrder = new ArrayList<>();
        int questionNumber = 1;
        boolean isFirstGroup = true;
        for (Map.Entry<String, List<QuestionExportDto>> entry : grouped.entrySet()) {
            // Start each group on a new page (except the first)
            if (!isFirstGroup) {
                sb.append("<div class=\"page-break\"></div>");
            }
            sb.append("<div class=\"type-section\">");
            sb.append("<h3>").append(formatQuestionType(entry.getKey())).append(" Questions</h3>");
            for (QuestionExportDto question : entry.getValue()) {
                renderQuestion(sb, question, questionNumber++, payload);
                renderOrder.add(question); // Track render order
            }
            sb.append("</div>");
            isFirstGroup = false;
        }
        return renderOrder; // Return grouped order
    }

    private void renderQuestion(StringBuilder sb, QuestionExportDto question, int number, ExportPayload payload) {
        sb.append("<div class=\"question\">");
        
        // For FILL_GAP questions, use content.text if available (contains prompt with underscores)
        String displayText = getDisplayText(question);
        
        sb.append("<div class=\"question-header\">").append(number).append(". ").append(escape(displayText)).append("</div>");
        
        // Question content (options, etc.)
        sb.append("<div class=\"question-content\">");
        renderQuestionContent(sb, question);
        sb.append("</div>");

        // Optional hint
        if (Boolean.TRUE.equals(payload.printOptions().includeHints()) && question.hint() != null && !question.hint().isBlank()) {
            sb.append("<div class=\"hint\">💡 <strong>Hint:</strong> ").append(escape(question.hint())).append("</div>");
        }

        // Optional explanation
        if (Boolean.TRUE.equals(payload.printOptions().includeExplanations()) && question.explanation() != null && !question.explanation().isBlank()) {
            sb.append("<div class=\"explanation\">ℹ️ <strong>Explanation:</strong> ").append(escape(question.explanation())).append("</div>");
        }

        sb.append("</div>");
    }

    private void renderQuestionContent(StringBuilder sb, QuestionExportDto question) {
        JsonNode content = question.content();
        if (content == null || content.isNull()) {
            return;
        }

        switch (question.type()) {
            case MCQ_SINGLE, MCQ_MULTI -> {
                if (content.has("options")) {
                    sb.append("<ul style=\"list-style:none;padding-left:0;\">");
                    int optionIdx = 0;
                    for (JsonNode option : content.get("options")) {
                        String text = option.has("text") ? option.get("text").asText() : "";
                        char label = (char) ('A' + optionIdx);
                        sb.append("<li><strong>").append(label).append(".</strong> ").append(escape(text)).append("</li>");
                        optionIdx++;
                    }
                    sb.append("</ul>");
                }
            }
            case TRUE_FALSE -> {
                sb.append("<ul style=\"list-style:none;padding-left:0;\">");
                sb.append("<li><strong>A.</strong> True</li>");
                sb.append("<li><strong>B.</strong> False</li>");
                sb.append("</ul>");
            }
            case FILL_GAP -> {
                // Render answer fields/blanks for users to fill in
                if (content.has("gaps")) {
                    sb.append("<ul style=\"list-style:none;padding-left:0;\">");
                    int gapNum = 1;
                    for (int i = 0; i < content.get("gaps").size(); i++) {
                        sb.append("<li><strong>").append(gapNum++).append(".</strong> _________________</li>");
                    }
                    sb.append("</ul>");
                }
            }
            case ORDERING -> {
                if (content.has("items")) {
                    sb.append("<ul style=\"list-style:none;padding-left:0;\">");
                    int itemIdx = 0;
                    for (JsonNode item : content.get("items")) {
                        String text = item.has("text") ? item.get("text").asText() : "";
                        char label = (char) ('A' + itemIdx);
                        sb.append("<li><strong>").append(label).append(".</strong> ").append(escape(text)).append("</li>");
                        itemIdx++;
                    }
                    sb.append("</ul>");
                }
            }
            case MATCHING -> {
                if (content.has("left") && content.has("right")) {
                    sb.append("<div class=\"matching-columns\">");
                    
                    // Left column with numbers
                    sb.append("<div class=\"matching-col\">");
                    sb.append("<h4>Column 1</h4>");
                    sb.append("<ul style=\"list-style:none;padding-left:0;\">");
                    int leftNum = 1;
                    for (JsonNode item : content.get("left")) {
                        String text = item.has("text") ? item.get("text").asText() : "";
                        sb.append("<li><strong>").append(leftNum++).append(".</strong> ").append(escape(text)).append("</li>");
                    }
                    sb.append("</ul></div>");
                    
                    // Right column with letters
                    sb.append("<div class=\"matching-col\">");
                    sb.append("<h4>Column 2</h4>");
                    sb.append("<ul style=\"list-style:none;padding-left:0;\">");
                    int rightIdx = 0;
                    for (JsonNode item : content.get("right")) {
                        String text = item.has("text") ? item.get("text").asText() : "";
                        char label = (char) ('A' + rightIdx);
                        sb.append("<li><strong>").append(label).append(".</strong> ").append(escape(text)).append("</li>");
                        rightIdx++;
                    }
                    sb.append("</ul></div>");
                    
                    sb.append("</div>");
                }
            }
            case HOTSPOT -> {
                if (content.has("imageUrl")) {
                    sb.append("<p>Image: ").append(escape(content.get("imageUrl").asText())).append("</p>");
                }
            }
            case COMPLIANCE -> {
                if (content.has("statements")) {
                    sb.append("<ul style=\"list-style:none;padding-left:0;\">");
                    int stmtNum = 1;
                    for (JsonNode statement : content.get("statements")) {
                        String text = statement.has("text") ? statement.get("text").asText() : "";
                        sb.append("<li><strong>").append(stmtNum++).append(".</strong> ").append(escape(text)).append("</li>");
                    }
                    sb.append("</ul>");
                }
            }
            case OPEN -> {
                sb.append("<p><em>Answer:</em></p><p>_____________________________________________________________________</p>");
            }
        }
    }

    /**
     * Format answer for display in HTML (appends to StringBuilder directly to handle HTML tags)
     */
    private void formatAnswerForDisplayHtml(StringBuilder sb, AnswerKeyEntry answer, QuestionExportDto question) {
        JsonNode normalized = answer.normalizedAnswer();
        if (normalized == null || normalized.isNull()) {
            sb.append("No answer");
            return;
        }
        
        switch (answer.type()) {
            case MCQ_SINGLE -> formatMcqSingleAnswerHtml(sb, normalized, question != null ? question.content() : null);
            case MCQ_MULTI -> formatMcqMultiAnswerHtml(sb, normalized, question != null ? question.content() : null);
            case TRUE_FALSE -> {
                if (normalized.has("answer")) {
                    sb.append(normalized.get("answer").asBoolean() ? "True" : "False");
                } else {
                    sb.append("N/A");
                }
            }
            case OPEN -> {
                if (normalized.has("answer") && !normalized.get("answer").isNull()) {
                    sb.append(escape(normalized.get("answer").asText()));
                } else {
                    sb.append("Open answer (manual grading)");
                }
            }
            case MATCHING -> formatMatchingAnswerHtml(sb, normalized, question != null ? question.content() : null);
            case ORDERING -> formatOrderingAnswerHtml(sb, normalized, question != null ? question.content() : null);
            case COMPLIANCE -> formatComplianceAnswerHtml(sb, normalized, question != null ? question.content() : null);
            case FILL_GAP -> formatFillGapAnswerHtml(sb, normalized);
            default -> sb.append(escape(normalized.toString()));
        }
    }
    
    private void formatMcqSingleAnswerHtml(StringBuilder sb, JsonNode normalized, JsonNode originalContent) {
        if (!normalized.has("correctOptionId")) {
            sb.append("N/A");
            return;
        }
        
        String correctId = normalized.get("correctOptionId").asText();
        
        // Find the option position and convert to letter
        if (originalContent != null && originalContent.has("options")) {
            int optionIdx = 0;
            for (JsonNode option : originalContent.get("options")) {
                String optionId = option.has("id") ? option.get("id").asText() : "";
                if (optionId.equals(correctId)) {
                    char label = (char) ('A' + optionIdx);
                    sb.append("<strong>").append(label).append("</strong>");
                    return;
                }
                optionIdx++;
            }
        }
        
        // Fallback to ID if text not found
        sb.append("Option: ").append(escape(correctId));
    }
    
    private void formatMcqMultiAnswerHtml(StringBuilder sb, JsonNode normalized, JsonNode originalContent) {
        if (!normalized.has("correctOptionIds")) {
            sb.append("N/A");
            return;
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
        
        // Format output
        if (!correctLetters.isEmpty()) {
            for (int i = 0; i < correctLetters.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("<strong>").append(correctLetters.get(i)).append("</strong>");
            }
        } else {
            // Fallback to IDs
            sb.append("Options: ").append(escape(correctIds.toString()));
        }
    }
    
    private void formatMatchingAnswerHtml(StringBuilder sb, JsonNode normalized, JsonNode originalContent) {
        if (!normalized.has("pairs")) {
            sb.append("N/A");
            return;
        }
        
        // Build ID-to-position lookup maps
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
                int position = 0; // Start at 0 for 'A'
                for (JsonNode item : originalContent.get("right")) {
                    int id = item.has("id") ? item.get("id").asInt() : 0;
                    rightIdToPosition.put(id, position++);
                }
            }
        }
        
        // Format pairs with numbers and letters (one per line)
        JsonNode pairs = normalized.get("pairs");
        int pairNum = 0;
        for (JsonNode pair : pairs) {
            if (pairNum > 0) sb.append("<br/>");
            
            int leftId = pair.has("leftId") ? pair.get("leftId").asInt() : 0;
            int rightId = pair.has("rightId") ? pair.get("rightId").asInt() : 0;
            
            Integer leftPos = leftIdToPosition.get(leftId);
            Integer rightPos = rightIdToPosition.get(rightId);
            
            if (leftPos != null && rightPos != null) {
                char rightLabel = (char) ('A' + rightPos);
                sb.append("<strong>").append(leftPos).append(" → ").append(rightLabel).append("</strong>");
            } else {
                sb.append(leftId).append(" → ").append(rightId);
            }
            pairNum++;
        }
    }
    
    private void formatOrderingAnswerHtml(StringBuilder sb, JsonNode normalized, JsonNode originalContent) {
        if (!normalized.has("order")) {
            sb.append("N/A");
            return;
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
        JsonNode order = normalized.get("order");
        int idx = 0;
        for (JsonNode item : order) {
            if (idx > 0) sb.append(" → ");
            int id = item.asInt();
            Integer position = itemIdToPosition.get(id);
            if (position != null) {
                char letter = (char) ('A' + position);
                sb.append("<strong>").append(letter).append("</strong>");
            } else {
                sb.append(id);
            }
            idx++;
        }
    }
    
    private void formatComplianceAnswerHtml(StringBuilder sb, JsonNode normalized, JsonNode originalContent) {
        // Check both field names (compliantIds and compliantStatementIds for compatibility)
        if (!normalized.has("compliantIds") && !normalized.has("compliantStatementIds")) {
            sb.append("N/A");
            return;
        }
        
        // Build ID-to-position lookup map
        Map<Integer, Integer> statementIdToPosition = new LinkedHashMap<>();
        if (originalContent != null && originalContent.has("statements")) {
            int position = 1;
            for (JsonNode stmt : originalContent.get("statements")) {
                int id = stmt.has("id") ? stmt.get("id").asInt() : 0;
                statementIdToPosition.put(id, position++);
            }
        }
        
        sb.append("Compliant: ");
        JsonNode ids = normalized.has("compliantIds") ? normalized.get("compliantIds") : normalized.get("compliantStatementIds");
        int idx = 0;
        for (JsonNode id : ids) {
            if (idx > 0) sb.append(", ");
            int stmtId = id.asInt();
            Integer position = statementIdToPosition.get(stmtId);
            if (position != null) {
                sb.append("<strong>").append(position).append("</strong>");
            } else {
                sb.append(stmtId);
            }
            idx++;
        }
    }
    
    private void formatFillGapAnswerHtml(StringBuilder sb, JsonNode normalized) {
        if (!normalized.has("answers")) {
            sb.append("N/A");
            return;
        }
        
        JsonNode answers = normalized.get("answers");
        int idx = 0;
        for (JsonNode answerObj : answers) {
            if (idx > 0) sb.append(", ");
            String text = answerObj.has("text") ? answerObj.get("text").asText() : "";
            sb.append("<strong>").append(idx + 1).append(".</strong> ").append(escape(text));
            idx++;
        }
    }

    private String formatQuestionType(String type) {
        return switch (type) {
            case "MCQ_SINGLE" -> "Multiple Choice (Single Answer)";
            case "MCQ_MULTI" -> "Multiple Choice (Multiple Answers)";
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
     * Get the display text for a question.
     * For FILL_GAP questions, prefer content.text and replace {N} placeholders with underscores.
     * Otherwise, use the generic questionText.
     */
    private String getDisplayText(QuestionExportDto question) {
        if (question.type() == uk.gegc.quizmaker.features.question.domain.model.QuestionType.FILL_GAP 
            && question.content() != null 
            && question.content().has("text") 
            && !question.content().get("text").asText().isBlank()) {
            String text = question.content().get("text").asText();
            // Replace {N} placeholders with underscores for export
            return text.replaceAll("\\{\\d+\\}", "____");
        }
        return question.questionText();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
