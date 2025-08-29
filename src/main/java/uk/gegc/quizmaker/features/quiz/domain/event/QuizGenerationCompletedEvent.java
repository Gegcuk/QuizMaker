package uk.gegc.quizmaker.features.quiz.domain.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
public class QuizGenerationCompletedEvent extends ApplicationEvent {
    
    private final UUID jobId;
    private final Map<Integer, List<Question>> chunkQuestions;
    private final GenerateQuizFromDocumentRequest originalRequest;
    private final List<Question> allQuestions;

    public QuizGenerationCompletedEvent(Object source, UUID jobId, 
                                      Map<Integer, List<Question>> chunkQuestions,
                                      GenerateQuizFromDocumentRequest originalRequest,
                                      List<Question> allQuestions) {
        super(source);
        this.jobId = jobId;
        this.chunkQuestions = chunkQuestions;
        this.originalRequest = originalRequest;
        this.allQuestions = allQuestions;
    }
} 