package uk.gegc.quizmaker.features.repetition.application;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;

import java.time.Duration;
import java.time.Instant;

@Component
public class RepetitionPriorityCalculator {

    public int compute(SpacedRepetitionEntry entry, Instant now){
        if(entry.getNextReviewAt() == null) return 0;

        long overdueDays = Math.max(0, Duration.between(entry.getNextReviewAt(), now).toDays());
        int gradeWeight = gradeWeight(entry.getLastGrade());

        int score = 20 + (int) (overdueDays * 5) + gradeWeight;
        return Math.min(100, score);
    }

    private int gradeWeight(RepetitionEntryGrade grade) {
        if(grade == null) return 0;
        return switch (grade){
            case AGAIN -> 30;
            case HARD -> 20;
            case GOOD -> 10;
            case EASY -> 0;
        };
    }

}
