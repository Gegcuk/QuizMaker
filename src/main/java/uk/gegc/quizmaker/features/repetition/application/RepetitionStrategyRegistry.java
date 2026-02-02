package uk.gegc.quizmaker.features.repetition.application;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionContentType;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RepetitionStrategyRegistry {

    private final Map<RepetitionContentType, RepetitionContentStrategy> strategies;

    public RepetitionStrategyRegistry(List<RepetitionContentStrategy> strategies){
        this.strategies = strategies.stream().collect(Collectors.toMap(RepetitionContentStrategy::supportedType, s -> s));
    }

    public RepetitionContentStrategy get(RepetitionContentType contentType){
        RepetitionContentStrategy strategy = strategies.get(contentType);
        if(strategy == null){
            throw new IllegalArgumentException("No strategy for type " + contentType);
        }
        return strategy;
    }
}
