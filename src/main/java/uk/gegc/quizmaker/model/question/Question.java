package uk.gegc.quizmaker.model.question;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.tag.Tag;

import java.time.Instant;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    QuestionType type;
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;
    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false, length = 20)
    private Difficulty difficulty;

    @Column(name = "question", nullable = false, length = 1000)
    private String questionText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", columnDefinition = "json", nullable = false)
    private String content;

    @Column(name = "hint", length = 500)
    private String hint;

    @Column(name = "explanation", length = 2000)
    private String explanation;

    @Column(name = "attachment_url", length = 2048)
    private String attachmentUrl;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "is_deleted",
            nullable = false,
            columnDefinition = "boolean default false",
            insertable = false)
    private Boolean isDeleted;

    @Column(name = "deleted_at")
    private Instant deletedAt;


    @ManyToMany(
            fetch = FetchType.LAZY,
            cascade = {CascadeType.PERSIST, CascadeType.MERGE}
    )
    @JoinTable(
            name = "quiz_questions",
            joinColumns = @JoinColumn(name = "question_id", nullable = false),
            inverseJoinColumns = @JoinColumn(name = "quiz_id", nullable = false)
    )
    private List<Quiz> quizId = new ArrayList<>();


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
