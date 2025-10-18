package uk.gegc.quizmaker.features.question.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Utility for shuffling AI-generated question content to remove positional bias.
 * 
 * <p>This component applies one-time shuffling to question content before persistence,
 * ensuring that AI-generated options, statements, and items are randomized while
 * preserving correctness metadata and relationships.</p>
 * 
 * <p>Supported question types:</p>
 * <ul>
 *   <li>MCQ_SINGLE/MCQ_MULTI: Shuffles options array</li>
 *   <li>ORDERING: Captures correctOrder BEFORE shuffling, then shuffles items for display</li>
 *   <li>MATCHING: Shuffles right column only</li>
 *   <li>COMPLIANCE: Shuffles statements array</li>
 *   <li>HOTSPOT: Shuffles regions array</li>
 * </ul>
 * 
 * <p>Types not shuffled: TRUE_FALSE, FILL_GAP, OPEN</p>
 * 
 * <p>Note: Questions are shuffled at THREE points:</p>
 * <ul>
 *   <li>1. Save time (here) - removes AI positional bias, captures correctOrder for ORDERING</li>
 *   <li>2. Attempt time (SafeQuestionContentBuilder) - prevents pattern recognition during quiz-taking</li>
 *   <li>3. Export time (QuizExportAssembler) - ensures each export has different order</li>
 * </ul>
 * 
 * <p>Educational note: One-time shuffling at save time removes AI positional bias
 * at the source with minimal runtime overhead and reduces the need for repeated
 * view-time shuffles.</p>
 */
@Slf4j
@Component
public class QuestionContentShuffler {
    
    private final ObjectMapper objectMapper;
    
    public QuestionContentShuffler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    /**
     * Shuffles question content based on question type using deterministic randomization.
     * 
     * @param contentJson the JSON content to shuffle
     * @param questionType the type of question determining shuffle strategy
     * @param randomSupplier supplier for deterministic random number generation
     * @return shuffled JSON content as string
     */
    public String shuffleContent(String contentJson, QuestionType questionType, Supplier<Random> randomSupplier) {
        if (contentJson == null || contentJson.isBlank()) {
            log.debug("Content is null or blank, skipping shuffle");
            return contentJson;
        }
        
        try {
            JsonNode root = objectMapper.readTree(contentJson);
            if (!root.isObject()) {
                log.debug("Content is not a JSON object, skipping shuffle");
                return contentJson;
            }
            
            ObjectNode contentNode = (ObjectNode) root;
            Random random = randomSupplier.get();
            
            switch (questionType) {
                case MCQ_SINGLE, MCQ_MULTI -> shuffleMcqOptions(contentNode, random);
                case ORDERING -> shuffleOrderingItems(contentNode, random);
                case MATCHING -> shuffleMatchingRightColumn(contentNode, random);
                case COMPLIANCE -> shuffleComplianceStatements(contentNode, random);
                case HOTSPOT -> shuffleHotspotRegions(contentNode, random);
                case TRUE_FALSE, FILL_GAP, OPEN -> {
                    // No shuffling needed for these types
                    log.trace("No shuffling applied for question type: {}", questionType);
                }
                default -> {
                    log.debug("Unknown question type for shuffling: {}", questionType);
                }
            }
            
            return objectMapper.writeValueAsString(contentNode);
            
        } catch (Exception e) {
            log.warn("Failed to shuffle content for type {}: {}", questionType, e.getMessage());
            return contentJson; // Return original content on failure
        }
    }
    
    /**
     * Shuffles MCQ options array while preserving option properties.
     */
    private void shuffleMcqOptions(ObjectNode contentNode, Random random) {
        JsonNode optionsNode = contentNode.get("options");
        if (optionsNode == null || !optionsNode.isArray()) {
            log.debug("No options array found for MCQ question");
            return;
        }
        
        ArrayNode optionsArray = (ArrayNode) optionsNode;
        List<JsonNode> optionsList = new ArrayList<>();
        
        // Convert to list for shuffling
        for (JsonNode option : optionsArray) {
            optionsList.add(option);
        }
        
        // Shuffle the list
        Collections.shuffle(optionsList, random);
        
        // Rebuild the array
        ArrayNode shuffledArray = objectMapper.createArrayNode();
        for (JsonNode option : optionsList) {
            shuffledArray.add(option);
        }
        
        contentNode.set("options", shuffledArray);
        log.trace("Shuffled {} MCQ options", optionsList.size());
    }
    
    /**
     * Shuffles ORDERING items and adds correctOrder field with canonical sequence.
     * CRITICAL: Captures correctOrder BEFORE shuffling to preserve AI's correct sequence.
     */
    private void shuffleOrderingItems(ObjectNode contentNode, Random random) {
        JsonNode itemsNode = contentNode.get("items");
        if (itemsNode == null || !itemsNode.isArray()) {
            log.debug("No items array found for ORDERING question");
            return;
        }
        
        ArrayNode itemsArray = (ArrayNode) itemsNode;
        List<JsonNode> itemsList = new ArrayList<>();
        
        // CRITICAL: Build correctOrder BEFORE shuffling
        // This captures the AI's provided sequence as the correct order
        ArrayNode correctOrder = objectMapper.createArrayNode();
        for (JsonNode item : itemsArray) {
            itemsList.add(item);
            // Store the original order as correctOrder
            if (item.has("id")) {
                correctOrder.add(item.get("id"));
            }
        }
        
        // Shuffle the items for display randomization
        Collections.shuffle(itemsList, random);
        
        // Rebuild the array
        ArrayNode shuffledArray = objectMapper.createArrayNode();
        for (JsonNode item : itemsList) {
            shuffledArray.add(item);
        }
        
        contentNode.set("items", shuffledArray);
        contentNode.set("correctOrder", correctOrder);
        log.trace("Shuffled {} ORDERING items and preserved correctOrder", itemsList.size());
    }
    
    /**
     * Shuffles MATCHING right column while preserving left.matchId relationships.
     */
    private void shuffleMatchingRightColumn(ObjectNode contentNode, Random random) {
        JsonNode rightNode = contentNode.get("right");
        if (rightNode == null || !rightNode.isArray()) {
            log.debug("No right array found for MATCHING question");
            return;
        }
        
        ArrayNode rightArray = (ArrayNode) rightNode;
        List<JsonNode> rightList = new ArrayList<>();
        
        // Convert to list for shuffling
        for (JsonNode rightItem : rightArray) {
            rightList.add(rightItem);
        }
        
        // Shuffle the right column
        Collections.shuffle(rightList, random);
        
        // Rebuild the array
        ArrayNode shuffledArray = objectMapper.createArrayNode();
        for (JsonNode rightItem : rightList) {
            shuffledArray.add(rightItem);
        }
        
        contentNode.set("right", shuffledArray);
        log.trace("Shuffled {} MATCHING right items", rightList.size());
    }
    
    /**
     * Shuffles COMPLIANCE statements while preserving compliance flags.
     */
    private void shuffleComplianceStatements(ObjectNode contentNode, Random random) {
        JsonNode statementsNode = contentNode.get("statements");
        if (statementsNode == null || !statementsNode.isArray()) {
            log.debug("No statements array found for COMPLIANCE question");
            return;
        }
        
        ArrayNode statementsArray = (ArrayNode) statementsNode;
        List<JsonNode> statementsList = new ArrayList<>();
        
        // Convert to list for shuffling
        for (JsonNode statement : statementsArray) {
            statementsList.add(statement);
        }
        
        // Shuffle the statements
        Collections.shuffle(statementsList, random);
        
        // Rebuild the array
        ArrayNode shuffledArray = objectMapper.createArrayNode();
        for (JsonNode statement : statementsList) {
            shuffledArray.add(statement);
        }
        
        contentNode.set("statements", shuffledArray);
        log.trace("Shuffled {} COMPLIANCE statements", statementsList.size());
    }
    
    /**
     * Shuffles HOTSPOT regions while preserving correct flags.
     */
    private void shuffleHotspotRegions(ObjectNode contentNode, Random random) {
        JsonNode regionsNode = contentNode.get("regions");
        if (regionsNode == null || !regionsNode.isArray()) {
            log.debug("No regions array found for HOTSPOT question");
            return;
        }
        
        ArrayNode regionsArray = (ArrayNode) regionsNode;
        List<JsonNode> regionsList = new ArrayList<>();
        
        // Convert to list for shuffling
        for (JsonNode region : regionsArray) {
            regionsList.add(region);
        }
        
        // Shuffle the regions
        Collections.shuffle(regionsList, random);
        
        // Rebuild the array
        ArrayNode shuffledArray = objectMapper.createArrayNode();
        for (JsonNode region : regionsList) {
            shuffledArray.add(region);
        }
        
        contentNode.set("regions", shuffledArray);
        log.trace("Shuffled {} HOTSPOT regions", regionsList.size());
    }
}
