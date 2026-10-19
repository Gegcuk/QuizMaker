package uk.gegc.quizmaker.features.quiz.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** One bounded private fact per generated quiz; its immutable creator owns it. */
@Entity
@Table(name = "quiz_owner_freshness")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizOwnerFreshness {
    @Id
    @Column(name = "quiz_id", nullable = false)
    private UUID quizId;

    @Column(name = "is_new", nullable = false)
    private boolean isNew;
}
