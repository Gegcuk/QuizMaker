package uk.gegc.quizmaker.service.user;

import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.model.user.User;

@Service
public interface UserService {
    public User createUser(User user);
}
