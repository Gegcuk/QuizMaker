package uk.gegc.quizmaker.features.repetition.domain.model;

import lombok.Getter;

@Getter
public enum RepetitionEntryGrade {
    AGAIN(0),
    HARD(3),
    GOOD(4),
    EASY(5);

    private final int sm2Value;

    RepetitionEntryGrade(int sm2Value){
        this.sm2Value=sm2Value;
    }

    public boolean isFail(){
        return sm2Value < 3;
    }
}
