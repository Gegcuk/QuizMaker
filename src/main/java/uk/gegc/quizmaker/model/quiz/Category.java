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
@Table(name="categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "category_id")
    private UUID id;

    @Size(min = 3, max = 100, message = "Category name length must be between 3 and 100 characters")
    @Column(name = "category_name", nullable = false)
    private String name;

    @Size(max = 1000, message = "Category description length must be between less than 1000 characters")
    @Column(name = "category_description")
    private String description;


}
