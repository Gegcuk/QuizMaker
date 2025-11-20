package uk.gegc.quizmaker.features.quizgroup.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;

import java.time.Instant;

@Entity
@Table(name = "quiz_group_memberships")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class QuizGroupMembership {

    @EmbeddedId
    private QuizGroupMembershipId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("groupId")
    @JoinColumn(name = "group_id", nullable = false, updatable = false)
    private QuizGroup group;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("quizId")
    @JoinColumn(name = "quiz_id", nullable = false, updatable = false)
    private Quiz quiz;

    @Column(name = "position", nullable = false)
    private Integer position;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;
}

