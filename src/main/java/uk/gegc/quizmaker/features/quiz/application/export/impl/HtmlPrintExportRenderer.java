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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><title>Quizzes Export</title>");
        sb.append("<style>");
        sb.append("body{font-family:sans-serif;margin:24px;line-height:1.6;} ");
        sb.append("h1{margin-bottom:0;} ");
        sb.append("h2{margin-top:32px;border-bottom:2px solid #333;padding-bottom:8px;} ");
        sb.append("h3{margin-top:24px;color:#555;} ");
        sb.append(".meta{color:#555;font-size:14px;} ");
        sb.append(".page-break{page-break-before:always;} ");
        sb.append(".question{margin:20px 0;padding:12px;background:#f9f9f9;border-left:3px solid #333;} ");
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
        sb.append(".matching-col ul{list-style:disc;padding-left:20px;margin:0;} ");
        sb.append(".matching-col li{padding:2px 0;} ");
        sb.append("ul{padding-left:20px;} ");
        sb.append("ul li{list-style-type:disc;} ");
        sb.append("</style>");
        sb.append("</head><body>");

        if (Boolean.TRUE.equals(payload.printOptions().includeCover())) {
            sb.append("<div class=\"cover\">");
            sb.append("<div class=\"meta\">Generated: ").append(java.time.Instant.now()).append("</div></div>");
        }

        // Collect all questions from all quizzes
        List<QuestionExportDto> allQuestions = new ArrayList<>();
        for (QuizExportDto quiz : payload.quizzes()) {
            if (payload.quizzes().size() > 1 || Boolean.TRUE.equals(payload.printOptions().includeMetadata())) {
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

        sb.append("<section class=\"answer-key\">");
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

    private void renderQuizHeader(StringBuilder sb, QuizExportDto quiz, ExportPayload payload) {
        sb.append("<section class=\"quiz\">");
        sb.append("<h2>").append(escape(quiz.title())).append("</h2>");
        
        if (Boolean.TRUE.equals(payload.printOptions().includeMetadata())) {
            sb.append("<div class=\"meta\">");
            if (quiz.category() != null) {
                sb.append("<span class=\"category\">Category: ").append(escape(quiz.category())).append("</span> ");
            }
            sb.append("<span>Difficulty: ").append(quiz.difficulty()).append("</span> ");
            sb.append("<span>Time: ").append(quiz.estimatedTime()).append(" min</span> ");
            if (quiz.tags() != null && !quiz.tags().isEmpty()) {
                sb.append("<span>Tags: ");
                quiz.tags().forEach(tag -> sb.append("<span class=\"tag\">#").append(escape(tag)).append("</span>"));
                sb.append("</span>");
            }
            sb.append("</div>");
        }
        
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
        for (Map.Entry<String, List<QuestionExportDto>> entry : grouped.entrySet()) {
            sb.append("<div class=\"type-section\">");
            sb.append("<h3>").append(formatQuestionType(entry.getKey())).append(" Questions</h3>");
            for (QuestionExportDto question : entry.getValue()) {
                renderQuestion(sb, question, questionNumber++, payload);
                renderOrder.add(question); // Track render order
            }
            sb.append("</div>");
        }
        return renderOrder; // Return grouped order
    }

    private void renderQuestion(StringBuilder sb, QuestionExportDto question, int number, ExportPayload payload) {
        sb.append("<div class=\"question\">");
        sb.append("<div class=\"question-header\">").append(number).append(". ").append(escape(question.questionText())).append("</div>");
        
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
                    sb.append("<ul>");
                    for (JsonNode option : content.get("options")) {
                        String text = option.has("text") ? option.get("text").asText() : "";
                        sb.append("<li>").append(escape(text)).append("</li>");
                    }
                    sb.append("</ul>");
                }
            }
            case TRUE_FALSE -> {
                sb.append("<ul><li>True</li><li>False</li></ul>");
            }
            case FILL_GAP -> {
                sb.append("<p><em>Fill in the blanks:</em></p>");
                if (content.has("gaps")) {
                    sb.append("<ul>");
                    int gapNum = 1;
                    for (int i = 0; i < content.get("gaps").size(); i++) {
                        sb.append("<li>Gap ").append(gapNum++).append(": _________________</li>");
                    }
                    sb.append("</ul>");
                }
            }
            case ORDERING -> {
                sb.append("<p><em>Order the following items:</em></p>");
                if (content.has("items")) {
                    sb.append("<ul>");
                    for (JsonNode item : content.get("items")) {
                        String text = item.has("text") ? item.get("text").asText() : "";
                        sb.append("<li>").append(escape(text)).append("</li>");
                    }
                    sb.append("</ul>");
                }
            }
            case MATCHING -> {
                sb.append("<p><em>Match the items:</em></p>");
                if (content.has("left") && content.has("right")) {
                    sb.append("<div class=\"matching-columns\">");
                    
                    // Left column
                    sb.append("<div class=\"matching-col\">");
                    sb.append("<h4>Left Column</h4>");
                    sb.append("<ul>");
                    for (JsonNode item : content.get("left")) {
                        String text = item.has("text") ? item.get("text").asText() : "";
                        sb.append("<li>").append(escape(text)).append("</li>");
                    }
                    sb.append("</ul></div>");
                    
                    // Right column
                    sb.append("<div class=\"matching-col\">");
                    sb.append("<h4>Right Column</h4>");
                    sb.append("<ul>");
                    for (JsonNode item : content.get("right")) {
                        String text = item.has("text") ? item.get("text").asText() : "";
                        sb.append("<li>").append(escape(text)).append("</li>");
                    }
                    sb.append("</ul></div>");
                    
                    sb.append("</div>");
                }
            }
            case HOTSPOT -> {
                sb.append("<p><em>Select the correct region on the image</em></p>");
                if (content.has("imageUrl")) {
                    sb.append("<p>Image: ").append(escape(content.get("imageUrl").asText())).append("</p>");
                }
            }
            case COMPLIANCE -> {
                sb.append("<p><em>Mark each statement as Compliant or Non-compliant:</em></p>");
                if (content.has("statements")) {
                    sb.append("<ul>");
                    for (JsonNode statement : content.get("statements")) {
                        String text = statement.has("text") ? statement.get("text").asText() : "";
                        sb.append("<li>").append(escape(text)).append("</li>");
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
            case MCQ_SINGLE -> {
                if (normalized.has("correctOptionId")) {
                    sb.append("Option: ").append(escape(normalized.get("correctOptionId").asText()));
                } else {
                    sb.append("N/A");
                }
            }
            case MCQ_MULTI -> {
                if (normalized.has("correctOptionIds")) {
                    sb.append("Options: ").append(escape(normalized.get("correctOptionIds").toString()));
                } else {
                    sb.append("N/A");
                }
            }
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
    
    private void formatMatchingAnswerHtml(StringBuilder sb, JsonNode normalized, JsonNode originalContent) {
        if (!normalized.has("pairs")) {
            sb.append("N/A");
            return;
        }
        
        // Build ID-to-text lookup maps
        Map<Integer, String> leftMap = new LinkedHashMap<>();
        Map<Integer, String> rightMap = new LinkedHashMap<>();
        
        if (originalContent != null) {
            if (originalContent.has("left")) {
                for (JsonNode item : originalContent.get("left")) {
                    int id = item.has("id") ? item.get("id").asInt() : 0;
                    String text = item.has("text") ? item.get("text").asText() : "";
                    leftMap.put(id, text);
                }
            }
            if (originalContent.has("right")) {
                for (JsonNode item : originalContent.get("right")) {
                    int id = item.has("id") ? item.get("id").asInt() : 0;
                    String text = item.has("text") ? item.get("text").asText() : "";
                    rightMap.put(id, text);
                }
            }
        }
        
        // Format pairs with text (one per line)
        JsonNode pairs = normalized.get("pairs");
        int pairNum = 0;
        for (JsonNode pair : pairs) {
            if (pairNum > 0) sb.append("<br/>");
            
            int leftId = pair.has("leftId") ? pair.get("leftId").asInt() : 0;
            int rightId = pair.has("rightId") ? pair.get("rightId").asInt() : 0;
            
            String leftText = leftMap.getOrDefault(leftId, String.valueOf(leftId));
            String rightText = rightMap.getOrDefault(rightId, String.valueOf(rightId));
            
            sb.append(escape(leftText)).append(" → ").append(escape(rightText));
            pairNum++;
        }
    }
    
    private void formatOrderingAnswerHtml(StringBuilder sb, JsonNode normalized, JsonNode originalContent) {
        if (!normalized.has("correctOrder")) {
            sb.append("N/A");
            return;
        }
        
        // Build ID-to-text lookup map
        Map<Integer, String> itemMap = new LinkedHashMap<>();
        if (originalContent != null && originalContent.has("items")) {
            for (JsonNode item : originalContent.get("items")) {
                int id = item.has("id") ? item.get("id").asInt() : 0;
                String text = item.has("text") ? item.get("text").asText() : "";
                itemMap.put(id, text);
            }
        }
        
        sb.append("Order: ");
        JsonNode order = normalized.get("correctOrder");
        int idx = 0;
        for (JsonNode item : order) {
            if (idx > 0) sb.append(", ");
            int id = item.asInt();
            String text = itemMap.getOrDefault(id, String.valueOf(id));
            sb.append(escape(text));
            idx++;
        }
    }
    
    private void formatComplianceAnswerHtml(StringBuilder sb, JsonNode normalized, JsonNode originalContent) {
        if (!normalized.has("compliantStatementIds")) {
            sb.append("N/A");
            return;
        }
        
        // Build ID-to-text lookup map
        Map<Integer, String> statementMap = new LinkedHashMap<>();
        if (originalContent != null && originalContent.has("statements")) {
            for (JsonNode stmt : originalContent.get("statements")) {
                int id = stmt.has("id") ? stmt.get("id").asInt() : 0;
                String text = stmt.has("text") ? stmt.get("text").asText() : "";
                statementMap.put(id, text);
            }
        }
        
        sb.append("Compliant: ");
        JsonNode ids = normalized.get("compliantStatementIds");
        int idx = 0;
        for (JsonNode id : ids) {
            if (idx > 0) sb.append(", ");
            int stmtId = id.asInt();
            String text = statementMap.getOrDefault(stmtId, String.valueOf(stmtId));
            sb.append(escape(text));
            idx++;
        }
    }
    
    private void formatFillGapAnswerHtml(StringBuilder sb, JsonNode normalized) {
        if (!normalized.has("gaps")) {
            sb.append("N/A");
            return;
        }
        
        JsonNode gaps = normalized.get("gaps");
        int idx = 0;
        for (JsonNode gap : gaps) {
            if (idx > 0) sb.append(", ");
            String answer = gap.has("answer") ? gap.get("answer").asText() : "";
            sb.append("Gap ").append(idx + 1).append(": ").append(escape(answer));
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

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
