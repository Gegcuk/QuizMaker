package uk.gegc.quizmaker.features.quiz.domain.model;

import jakarta.persistence.*;
import lombok.NoArgsConstructor;

/** One short transaction lock serializes acceptance and global admission. */
@Entity
@Table(name = "quiz_generation_admission")
@NoArgsConstructor
public class QuizGenerationAdmission {
    @Id
    private int id;
    @Column(name = "next_sequence", nullable = false)
    private long nextSequence;

    public long allocate() {
        return nextSequence++;
    }
}
