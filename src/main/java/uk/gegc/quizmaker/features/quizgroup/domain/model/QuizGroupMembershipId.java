package uk.gegc.quizmaker.features.quizgroup.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class QuizGroupMembershipId implements Serializable {

    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "quiz_id")
    private UUID quizId;
}

