package uk.gegc.quizmaker.service.scoring;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.question.domain.model.Answer;

@Service
@RequiredArgsConstructor
public class ScoringService {

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
