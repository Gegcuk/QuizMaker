package uk.gegc.quizmaker.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
public class UserDetailsServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserDetailsServiceImpl userDetailsServiceImpl;

    @Test
    @DisplayName("loadUserByUsername: happy when user exists by username")
    void loadUserByUsername_happy() {
        User user = new User();
        user.setUsername("johndoe");
        user.setHashedPassword("hashedPassword");
        user.setActive(true);
        Role role = Role.builder()
                .roleId(1L)
                .roleName("ROLE_USER")
                .build();
        user.setRoles(Set.of(role));

        when(userRepository.findByUsernameWithRoles("johndoe")).thenReturn(Optional.of(user));

        UserDetails userDetails = userDetailsServiceImpl.loadUserByUsername("johndoe");

        assertEquals("johndoe", userDetails.getUsername());
        assertEquals("hashedPassword", userDetails.getPassword());
        assertTrue(
                userDetails.getAuthorities()
                        .stream()
                        .anyMatch(authority -> authority.getAuthority().equals("ROLE_USER"))
        );

        verify(userRepository).findByUsernameWithRoles("johndoe");
        verify(userRepository, never()).findByEmailWithRoles(any());
    }

    @Test
    @DisplayName("loadUserByUsername: happy when user exists by email")
    void loadUserByUsername_byEmail_happy() {
        User user = new User();
        user.setUsername("janedoe");
        user.setHashedPassword("hashed2");
        user.setActive(true);
        Role admin = Role.builder()
                .roleId(2L)
                .roleName("ROLE_ADMIN")
                .build();
        user.setRoles(Set.of(admin));

        when(userRepository.findByUsernameWithRoles("jane@example.com"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmailWithRoles("jane@example.com"))
                .thenReturn(Optional.of(user));

        UserDetails ud = userDetailsServiceImpl.loadUserByUsername("jane@example.com");

        assertEquals("janedoe", ud.getUsername());
        assertEquals("hashed2", ud.getPassword());
        assertTrue(
                ud.getAuthorities()
                        .stream()
                        .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))
        );
        verify(userRepository).findByUsernameWithRoles("jane@example.com");
        verify(userRepository).findByEmailWithRoles("jane@example.com");
    }

    @Test
    @DisplayName("loadUserByUsername: sad when user not found")
    void loadUserByUsername_notFound() {
        // given
        when(userRepository.findByUsernameWithRoles("missing")).thenReturn(Optional.empty());
        when(userRepository.findByEmailWithRoles("missing")).thenReturn(Optional.empty());

        assertThrows(
                UsernameNotFoundException.class,
                () -> userDetailsServiceImpl.loadUserByUsername("missing")
        );
        verify(userRepository).findByUsernameWithRoles("missing");
        verify(userRepository).findByEmailWithRoles("missing");
    }

}
