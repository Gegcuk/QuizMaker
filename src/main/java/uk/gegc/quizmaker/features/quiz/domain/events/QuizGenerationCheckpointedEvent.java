package uk.gegc.quizmaker.features.quiz.domain.events;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class QuizGenerationCheckpointedEvent extends ApplicationEvent {

    private final UUID jobId;

    public QuizGenerationCheckpointedEvent(Object source, UUID jobId) {
        super(source);
        this.jobId = jobId;
    }
}
