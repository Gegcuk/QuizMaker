package uk.gegc.quizmaker.model.user;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "users")
@Table(name="roles")
public class Role {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "roleId")
    private Long roleId;

    @Column(name = "role_name", nullable = false, unique = true)
    private String role;

    @ManyToMany(mappedBy = "roles")
    private List<User> users;
}
