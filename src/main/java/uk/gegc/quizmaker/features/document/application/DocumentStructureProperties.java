package uk.gegc.quizmaker.features.document.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Configuration knobs for document structure extraction, including Day 6 hierarchical passes.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "document-structure")
public class DocumentStructureProperties {

    /**
     * If canonical text exceeds this character length, enable hierarchical processing.
     */
    private int longDocThresholdChars = 60000;

    /**
     * Target slice size (characters) for pass 1 slicing.
     */
    private int pass1SliceSizeChars = 45000;

    /**
     * Overlap percentage (0-100) between adjacent slices for pass 1.
     */
    private int pass1OverlapPercent = 5;

    /**
     * Maximum outline depth when extracting over the whole document (root pass).
     */
    private int maxDepthRoot = 2; // up to CHAPTER

    /**
     * Maximum outline depth when extracting inside a chapter (sections/subsections).
     */
    private int maxDepthPerChapter = 3; // SECTION/SUBSECTION

    /**
     * Whether to add paragraph nodes using heuristics before falling back to LLM.
     */
    private boolean paragraphHeuristicsEnabled = true;

    /**
     * Minimum and maximum characters per paragraph chunk in heuristic mode.
     */
    private int paragraphMinChars = 200;
    private int paragraphMaxChars = 1800;

    /**
     * Number of words to use for paragraph start/end anchors in heuristic mode.
     */
    private int paragraphAnchorWords = 8;

    /**
     * Similarity threshold (0.0-1.0) for merging similar top-level nodes.
     */
    private double topLevelSimilarityThreshold = 0.8;

    /**
     * Optional model names for tiering (used if supported by AI client).
     */
    private String defaultOutlineModel = "auto";
    private String longContextOutlineModel = "auto-long";

    /**
     * Top-level node types that should be treated as document-level structure.
     * Default includes common book structure elements.
     */
    private List<String> topLevelTypes = List.of("PART", "CHAPTER", "PROLOGUE", "FOREWORD", "APPENDIX", "EPILOGUE");
}


