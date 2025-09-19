package uk.gegc.quizmaker.features.quiz.domain.events;

import org.springframework.context.ApplicationEvent;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;

import java.util.UUID;

/**
 * Domain event published when a quiz generation job has been created and is ready to be processed.
 * The event is handled after the surrounding transaction commits to ensure the job is visible to
 * asynchronous workers.
 */
public class QuizGenerationRequestedEvent extends ApplicationEvent {

    private final UUID jobId;
    private final GenerateQuizFromDocumentRequest request;

    public QuizGenerationRequestedEvent(Object source, UUID jobId, GenerateQuizFromDocumentRequest request) {
        super(source);
        this.jobId = jobId;
        this.request = request;
    }

    public UUID getJobId() {
        return jobId;
    }

    public GenerateQuizFromDocumentRequest getRequest() {
        return request;
    }
}
