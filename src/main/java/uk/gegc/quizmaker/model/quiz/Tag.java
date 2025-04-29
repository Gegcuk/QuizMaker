package uk.gegc.quizmaker.model.quiz;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "tags")
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "tag_id", updatable = false, nullable = false)
    private UUID id;

    @Size(min = 3, max = 50, message = "Tag length must be between 3 and 50 characters")
    @Column(name = "tag_name", length = 50, nullable = false, unique = true)
    private String tagName;

    @Size(min=3, max = 500, message = "Tag description length must be between 3 and 500 characters")
    @Column(name = "tag_description", length = 500)
    private String tagDescription;

}
