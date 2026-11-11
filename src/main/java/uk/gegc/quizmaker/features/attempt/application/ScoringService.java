package uk.gegc.quizmaker.features.attempt.application;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.attempt.domain.model.AcceptedAnswerFact;
import java.util.List;
import uk.gegc.quizmaker.features.question.domain.model.Answer;

@Service
@RequiredArgsConstructor
public class ScoringService {

    public double computeAndPersistScore(Attempt attempt, List<AcceptedAnswerFact> answers) {
        double total = answers.stream().mapToDouble(answer -> answer.score() != null ? answer.score() : 0.0).sum();
        attempt.setTotalScore(total);
        return total;
    }

    public long countCorrect(List<AcceptedAnswerFact> answers) {
        return answers.stream().filter(AcceptedAnswerFact::isCorrect).count();
    }

    @Transactional
    public double computeAndPersistScore(Attempt attempt) {
        double total = attempt.getAnswers().stream()
                .mapToDouble(a -> a.getScore() != null ? a.getScore() : 0.0)
                .sum();
        attempt.setTotalScore(total);
        return total;
    }

    public long countCorrect(Attempt attempt) {
        return attempt.getAnswers().stream()
                .filter(Answer::getIsCorrect)
                .count();
    }
}
