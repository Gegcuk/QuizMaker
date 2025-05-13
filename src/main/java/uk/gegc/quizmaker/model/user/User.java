package uk.gegc.quizmaker.model.user;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor @AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
@Table(name = "users")
public class User implements Persistable<UUID> {

    @Id
    @Column(name = "user_id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank
    @Size(min = 4, max = 20, message = "Username must be 4–20 characters")
    @Column(name = "username", unique = true, nullable = false)
    private String username;

    @NotBlank
    @Email
    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "password", nullable = false)
    private String hashedPassword;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_login")
    private LocalDateTime lastLoginDate;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @ManyToMany
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private List<Role> roles;

    // ——— Persistable support ———
    // if you manually set `id`, JPA will treat this as new:
    @Transient
    private boolean isNew = true;

    @Override
    @Transient
    public boolean isNew() {
        return this.isNew;
    }

    @PostLoad @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.isNew     = true;
    }

    @PreUpdate
    void preUpdate() {
        if (!this.isDeleted) {
            this.updatedAt = LocalDateTime.now();
        } else {
            this.deletedAt = LocalDateTime.now();
        }
    }
}