package uk.gegc.quizmaker.model.question;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.quiz.Tag;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "questions")
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToMany(
            fetch = FetchType.LAZY,
            cascade = {CascadeType.PERSIST, CascadeType.MERGE}
    )
    @JoinTable(
            name = "quiz_questions",
            joinColumns = @JoinColumn(name = "question_id", nullable = false),
            inverseJoinColumns = @JoinColumn(name = "quiz_id", nullable = false)
    )
    @NotNull
    private List<Quiz> quizId = new ArrayList<>();

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    QuestionType type;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false, length = 20)
    private Difficulty difficulty;

    @NotBlank
    @Size(max = 1000, message = "Question text length must be less than 1000 characters")
    @Column(name = "question", nullable = false, length = 1000)
    private String questionText;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", columnDefinition = "json", nullable = false)
    private String content;

    @Size(max = 500, message = "Hint length must be less than 500 characters")
    @Column(name = "hint", length = 500)
    private String hint;

    @Size(max = 2000, message = "Explanation must be less than 2000 characters")
    @Column(name = "explanation", length = 2000)
    private String explanation;

    @Size(max = 2048, message = "URL length is limited by 2048 characters")
    @Column(name = "attachment_url", length = 2048)
    private String attachmentUrl;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false, columnDefinition = "boolean default false")
    private Boolean isDeleted;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @ManyToMany(
            fetch = FetchType.LAZY,
            cascade = {CascadeType.PERSIST, CascadeType.MERGE}
    )
    @JoinTable(
            name = "question_tags",
            joinColumns = @JoinColumn(name = "question_id", nullable = false),
            inverseJoinColumns = @JoinColumn(name = "tag_id", nullable = false)
    )
    private List<Tag> tags = new ArrayList<>();

}
