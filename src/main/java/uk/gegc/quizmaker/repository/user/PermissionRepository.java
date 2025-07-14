package uk.gegc.quizmaker.repository.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.model.user.Permission;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {
    
    Optional<Permission> findByPermissionName(String permissionName);
    
    List<Permission> findByResource(String resource);
    
    boolean existsByPermissionName(String permissionName);
    
    List<Permission> findByResourceAndAction(String resource, String action);
}
