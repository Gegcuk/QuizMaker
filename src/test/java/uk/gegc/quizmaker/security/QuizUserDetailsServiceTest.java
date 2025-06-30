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
import uk.gegc.quizmaker.model.user.Role;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.user.UserRepository;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
public class QuizUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private QuizUserDetailsService quizUserDetailsService;

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

        when(userRepository.findByUsername("johndoe")).thenReturn(Optional.of(user));

        UserDetails userDetails = quizUserDetailsService.loadUserByUsername("johndoe");

        assertEquals("johndoe", userDetails.getUsername());
        assertEquals("hashedPassword", userDetails.getPassword());
        assertTrue(
                userDetails.getAuthorities()
                        .stream()
                        .anyMatch(authority -> authority.getAuthority().equals("ROLE_USER"))
        );

        verify(userRepository).findByUsername("johndoe");
        verify(userRepository, never()).findByEmail(any());
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

        when(userRepository.findByUsername("jane@example.com"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("jane@example.com"))
                .thenReturn(Optional.of(user));

        UserDetails ud = quizUserDetailsService.loadUserByUsername("jane@example.com");

        assertEquals("janedoe", ud.getUsername());
        assertEquals("hashed2", ud.getPassword());
        assertTrue(
                ud.getAuthorities()
                        .stream()
                        .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))
        );
        verify(userRepository).findByUsername("jane@example.com");
        verify(userRepository).findByEmail("jane@example.com");
    }

    @Test
    @DisplayName("loadUserByUsername: sad when user not found")
    void loadUserByUsername_notFound() {
        // given
        when(userRepository.findByUsername("missing")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("missing")).thenReturn(Optional.empty());

        assertThrows(
                UsernameNotFoundException.class,
                () -> quizUserDetailsService.loadUserByUsername("missing")
        );
        verify(userRepository).findByUsername("missing");
        verify(userRepository).findByEmail("missing");
    }

}
