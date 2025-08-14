package uk.gegc.quizmaker.features.tag.domain.model;

import jakarta.persistence.*;
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

    @Column(name = "tag_name", length = 50, nullable = false, unique = true)
    private String name;

    @Column(name = "tag_description", length = 500)
    private String description;

}
