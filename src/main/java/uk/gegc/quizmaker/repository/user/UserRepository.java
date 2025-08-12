package uk.gegc.quizmaker.repository.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.model.user.User;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /**
     * Find user by username with roles eagerly fetched to avoid N+1 queries
     */
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.roles WHERE u.username = :username")
    Optional<User> findByUsernameWithRoles(@Param("username") String username);

    /**
     * Find user by email with roles eagerly fetched to avoid N+1 queries
     */
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.roles WHERE LOWER(u.email) = LOWER(:email)")
    Optional<User> findByEmailWithRoles(@Param("email") String email);

    /**
     * Find user by ID with roles eagerly fetched to avoid N+1 queries
     */
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.roles WHERE u.id = :id")
    Optional<User> findByIdWithRoles(@Param("id") UUID id);

    /**
     * Find user by username with roles and permissions eagerly fetched to avoid N+1 queries
     */
    @Query("SELECT DISTINCT u FROM User u " +
           "LEFT JOIN FETCH u.roles r " +
           "LEFT JOIN FETCH r.permissions " +
           "WHERE u.username = :username")
    Optional<User> findByUsernameWithRolesAndPermissions(@Param("username") String username);

    /**
     * Find user by ID with roles and permissions eagerly fetched to avoid N+1 queries
     */
    @Query("SELECT DISTINCT u FROM User u " +
           "LEFT JOIN FETCH u.roles r " +
           "LEFT JOIN FETCH r.permissions " +
           "WHERE u.id = :id")
    Optional<User> findByIdWithRolesAndPermissions(@Param("id") UUID id);

}
