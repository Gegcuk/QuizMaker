package uk.gegc.quizmaker.service.user;

import org.springframework.web.multipart.MultipartFile;

public interface AvatarService {
    String uploadAndAssignAvatar(String username, MultipartFile file);
}


