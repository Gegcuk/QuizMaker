package uk.gegc.quizmaker.model.quiz;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.user.User;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name="quizzes", uniqueConstraints = @UniqueConstraint(columnNames = {"creator_id", "title"}))
@SQLDelete(sql = "UPDATE quizzes SET is_deleted = true, deleted_at = CURRENT_TIMESTAMP WHERE quiz_id = ?")
@SQLRestriction("is_deleted = false")
public class Quiz {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name="quiz_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false, updatable = false)
    private User creator;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Size(min = 3, max = 100, message = "Title length must be between 3 and 100 characters")
    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Size(max = 1000, message = "Description must be at most 1000 characters long")
    @Column(name = "description")
    private String description;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private Visibility visibility;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false, length = 20)
    private Difficulty difficulty;

    @NotNull
    @Min(value = 1, message = "Estimated time can't be less than 1 minute")
    @Max(value = 180, message = "Estimated time can't be more than 180 minutes")
    @Column(name = "estimated_time_min", nullable = false)
    private Integer estimatedTime;

    @NotNull
    @Column(name = "is_repetition_enabled", nullable = false)
    private Boolean isRepetitionEnabled;

    @NotNull
    @Column(name = "is_timer_enabled", nullable = false)
    private Boolean timerEnabled;

    @Min(value = 1, message = "Timer duration must be at least 1 minute")
    @Max(value = 180, message = "Timer duration must be at most 180 minutes")
    @Column(name = "timer_duration_min")
    private Integer timerDuration;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "is_deleted")
    private Boolean isDeleted;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @ManyToMany(
            fetch = FetchType.LAZY,
            cascade = {CascadeType.PERSIST, CascadeType.MERGE}
    )
    @JoinTable(
            name = "quiz_tags",
            joinColumns = @JoinColumn(name = "quiz_id", nullable = false),
            inverseJoinColumns = @JoinColumn(name = "tag_id", nullable = false)
    )
    private List<Tag> tags = new ArrayList<>();

    @PreRemove
    private void onSoftDelete() {
        this.isDeleted = true;
        this.deletedAt = LocalDateTime.now();
    }

}
