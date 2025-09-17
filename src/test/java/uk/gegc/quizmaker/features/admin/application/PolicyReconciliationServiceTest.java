package uk.gegc.quizmaker.features.admin.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.admin.aplication.PermissionService;
import uk.gegc.quizmaker.features.admin.application.impl.PolicyReconciliationServiceImpl;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.repository.PermissionRepository;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PolicyReconciliationServiceTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private PermissionService permissionService;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private PolicyReconciliationServiceImpl policyReconciliationService;

    private Role testRole;
    private Permission testPermission;

    @BeforeEach
    void setUp() {
        testPermission = Permission.builder()
                .permissionId(1L)
                .permissionName(PermissionName.QUIZ_READ.name())
                .description("View quizzes")
                .resource("quiz")
                .action("read")
                .build();

        testRole = Role.builder()
                .roleId(1L)
                .roleName("ROLE_USER")
                .description("Basic user role")
                .isDefault(true)
                .permissions(Set.of(testPermission))
                .build();
    }

    @Test
    @DisplayName("getManifestVersion: when manifest exists then returns version")
    void getManifestVersion_whenManifestExists_thenReturnsVersion() throws IOException {
        // Given
        JsonNode manifest = createMockManifest();
        when(objectMapper.readTree(any(InputStream.class))).thenReturn(manifest);

        // When
        String version = policyReconciliationService.getManifestVersion();

        // Then
        assertThat(version).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("getManifestVersion: when manifest read fails then returns unknown")
    void getManifestVersion_whenManifestReadFails_thenReturnsUnknown() throws IOException {
        // Given
        when(objectMapper.readTree(any(InputStream.class))).thenThrow(new IOException("File not found"));

        // When
        String version = policyReconciliationService.getManifestVersion();

        // Then
        assertThat(version).isEqualTo("unknown");
    }

    @Test
    @DisplayName("isInSync: when database matches manifest then returns true")
    void isInSync_whenDatabaseMatchesManifest_thenReturnsTrue() throws IOException {
        // Given
        JsonNode manifest = createMockManifest();
        when(objectMapper.readTree(any(InputStream.class))).thenReturn(manifest);
        when(permissionRepository.findAll()).thenReturn(List.of(testPermission));
        when(roleRepository.findAllWithPermissions()).thenReturn(List.of(testRole));

        // When
        boolean inSync = policyReconciliationService.isInSync();

        // Then
        assertThat(inSync).isTrue();
    }

    @Test
    @DisplayName("isInSync: when database differs from manifest then returns false")
    void isInSync_whenDatabaseDiffersFromManifest_thenReturnsFalse() throws IOException {
        // Given
        JsonNode manifest = createMockManifest();
        when(objectMapper.readTree(any(InputStream.class))).thenReturn(manifest);
        
        // Database has different permissions than manifest
        Permission extraPermission = Permission.builder()
                .permissionId(2L)
                .permissionName("EXTRA_PERMISSION")
                .build();
        when(permissionRepository.findAll()).thenReturn(List.of(testPermission, extraPermission));
        when(roleRepository.findAllWithPermissions()).thenReturn(List.of(testRole));

        // When
        boolean inSync = policyReconciliationService.isInSync();

        // Then
        assertThat(inSync).isFalse();
    }

    @Test
    @DisplayName("getPolicyDiff: when called then returns detailed diff")
    void getPolicyDiff_whenCalled_thenReturnsDetailedDiff() throws IOException {
        // Given
        JsonNode manifest = createMockManifest();
        when(objectMapper.readTree(any(InputStream.class))).thenReturn(manifest);
        when(permissionRepository.findAll()).thenReturn(List.of(testPermission));
        when(roleRepository.findAllWithPermissions()).thenReturn(List.of(testRole));

        // When
        PolicyReconciliationService.PolicyDiff diff = policyReconciliationService.getPolicyDiff();

        // Then
        assertThat(diff).isNotNull();
        assertThat(diff.manifestVersion()).isEqualTo("1.0.0");
        assertThat(diff.isInSync()).isTrue();
        assertThat(diff.missingPermissions()).isEmpty();
        assertThat(diff.extraPermissions()).isEmpty();
        assertThat(diff.missingRoles()).isEmpty();
        assertThat(diff.extraRoles()).isEmpty();
        assertThat(diff.rolePermissionMismatches()).isEmpty();
    }

    @Test
    @DisplayName("reconcileRole: when role exists then updates role")
    void reconcileRole_whenRoleExists_thenUpdatesRole() throws IOException {
        // Given
        JsonNode manifest = createMockManifest();
        when(objectMapper.readTree(any(InputStream.class))).thenReturn(manifest);
        when(roleRepository.findByRoleNameWithPermissions("ROLE_USER")).thenReturn(Optional.of(testRole));

        // When
        PolicyReconciliationService.ReconciliationResult result = policyReconciliationService.reconcileRole("ROLE_USER");

        // Then
        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("Role reconciliation completed");
        // Note: When role exists and permissions match, no save is needed
    }

    @Test
    @DisplayName("reconcileRole: when role does not exist then creates role")
    void reconcileRole_whenRoleDoesNotExist_thenCreatesRole() throws IOException {
        // Given
        JsonNode manifest = createMockManifest();
        when(objectMapper.readTree(any(InputStream.class))).thenReturn(manifest);
        when(roleRepository.findByRoleNameWithPermissions("ROLE_USER"))
                .thenReturn(Optional.empty())  // First call - role doesn't exist
                .thenReturn(Optional.of(testRole));  // Second call - role exists after save
        when(roleRepository.save(any(Role.class))).thenReturn(testRole);

        // When
        PolicyReconciliationService.ReconciliationResult result = policyReconciliationService.reconcileRole("ROLE_USER");

        // Then
        assertThat(result.success()).isTrue();
        assertThat(result.rolesAdded()).isEqualTo(1);
        verify(roleRepository).save(any(Role.class));
    }

    @Test
    @DisplayName("reconcileRole: when role not in manifest then returns failure")
    void reconcileRole_whenRoleNotInManifest_thenReturnsFailure() throws IOException {
        // Given
        JsonNode manifest = createMockManifest();
        when(objectMapper.readTree(any(InputStream.class))).thenReturn(manifest);

        // When
        PolicyReconciliationService.ReconciliationResult result = policyReconciliationService.reconcileRole("INVALID_ROLE");

        // Then
        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("Role reconciliation failed");
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    @DisplayName("reconcileAll: when called then reconciles all roles and permissions")
    void reconcileAll_whenCalled_thenReconcilesAllRolesAndPermissions() throws IOException {
        // Given
        JsonNode manifest = createMockManifest();
        when(objectMapper.readTree(any(InputStream.class))).thenReturn(manifest);
        when(permissionService.permissionExists(anyString())).thenReturn(false);
        when(roleRepository.findByRoleNameWithPermissions(anyString()))
                .thenReturn(Optional.empty())  // First call - role doesn't exist
                .thenReturn(Optional.of(testRole));  // Second call - role exists after save
        when(roleRepository.save(any(Role.class))).thenReturn(testRole);

        // When
        PolicyReconciliationService.ReconciliationResult result = policyReconciliationService.reconcileAll();

        // Then
        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("Policy reconciliation completed successfully");
        verify(permissionService, atLeastOnce()).createPermission(anyString(), anyString(), anyString(), anyString());
        verify(roleRepository, atLeastOnce()).save(any(Role.class));
    }

    private JsonNode createMockManifest() throws IOException {
        String manifestJson = """
            {
                "version": "1.0.0",
                "description": "Test manifest",
                "roles": {
                    "ROLE_USER": {
                        "name": "ROLE_USER",
                        "description": "Basic user role",
                        "isDefault": true,
                        "permissions": ["QUIZ_READ"]
                    }
                }
            }
            """;
        return new ObjectMapper().readTree(manifestJson);
    }
}