package uk.gegc.quizmaker.features.admin.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.admin.application.impl.RolePermissionAuditServiceImpl;
import uk.gegc.quizmaker.features.admin.domain.model.RolePermissionAudit;
import uk.gegc.quizmaker.features.admin.domain.repository.RolePermissionAuditRepository;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RolePermissionAuditServiceTest {

    @Mock
    private RolePermissionAuditRepository auditRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private RolePermissionAuditServiceImpl auditService;

    private User testActor;
    private User testTargetUser;
    private Role testRole;

    @BeforeEach
    void setUp() {
        testActor = new User();
        testActor.setId(UUID.randomUUID());
        testActor.setUsername("admin");

        testTargetUser = new User();
        testTargetUser.setId(UUID.randomUUID());
        testTargetUser.setUsername("user");

        testRole = new Role();
        testRole.setRoleId(1L);
        testRole.setRoleName("ROLE_USER");
    }

    @Test
    @DisplayName("logRoleAssigned: when called then saves audit entry")
    void logRoleAssigned_whenCalled_thenSavesAuditEntry() {
        // Given
        String reason = "Role assignment test";
        String correlationId = "test-correlation-id";
        String ipAddress = "127.0.0.1";
        String userAgent = "test-agent";

        // When
        auditService.logRoleAssigned(testActor, testTargetUser, testRole, reason, correlationId, ipAddress, userAgent);

        // Then
        verify(auditRepository).save(any(RolePermissionAudit.class));
    }

    @Test
    @DisplayName("logRoleRemoved: when called then saves audit entry")
    void logRoleRemoved_whenCalled_thenSavesAuditEntry() {
        // Given
        String reason = "Role removal test";
        String correlationId = "test-correlation-id";
        String ipAddress = "127.0.0.1";
        String userAgent = "test-agent";

        // When
        auditService.logRoleRemoved(testActor, testTargetUser, testRole, reason, correlationId, ipAddress, userAgent);

        // Then
        verify(auditRepository).save(any(RolePermissionAudit.class));
    }

    @Test
    @DisplayName("logRoleCreated: when called then saves audit entry")
    void logRoleCreated_whenCalled_thenSavesAuditEntry() {
        // Given
        String reason = "Role creation test";
        String correlationId = "test-correlation-id";
        String ipAddress = "127.0.0.1";
        String userAgent = "test-agent";

        // When
        auditService.logRoleCreated(testActor, testRole, reason, correlationId, ipAddress, userAgent);

        // Then
        verify(auditRepository).save(any(RolePermissionAudit.class));
    }

    @Test
    @DisplayName("logRoleDeleted: when called then saves audit entry")
    void logRoleDeleted_whenCalled_thenSavesAuditEntry() {
        // Given
        String reason = "Role deletion test";
        String correlationId = "test-correlation-id";
        String ipAddress = "127.0.0.1";
        String userAgent = "test-agent";

        // When
        auditService.logRoleDeleted(testActor, testRole, reason, correlationId, ipAddress, userAgent);

        // Then
        verify(auditRepository).save(any(RolePermissionAudit.class));
    }

    @Test
    @DisplayName("logPolicyReconciled: when called then saves audit entry")
    void logPolicyReconciled_whenCalled_thenSavesAuditEntry() {
        // Given
        String reason = "Policy reconciliation test";
        String correlationId = "test-correlation-id";
        String ipAddress = "127.0.0.1";
        String userAgent = "test-agent";

        // When
        auditService.logPolicyReconciled(testActor, reason, correlationId, ipAddress, userAgent);

        // Then
        verify(auditRepository).save(any(RolePermissionAudit.class));
    }
}
