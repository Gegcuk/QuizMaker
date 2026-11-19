package uk.gegc.quizmaker.features.learning.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** No implicit production batch size. Missing/invalid configuration keeps this dark port unavailable. */
@Component
@ConfigurationProperties(prefix = "quizmaker.learning.due")
public class LearningDueSelectionConfig {
    private Integer maxBatchSize;
    public Integer getMaxBatchSize() { return maxBatchSize; }
    public void setMaxBatchSize(Integer maxBatchSize) { this.maxBatchSize = maxBatchSize; }
}
