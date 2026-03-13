package com.example.hms.service;

import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.BootstrapSignupRequest;
import com.example.hms.payload.dto.BootstrapSignupResponse;
import com.example.hms.payload.dto.UpdateUserRequestDTO;
import com.example.hms.payload.dto.UserRequestDTO;
import com.example.hms.payload.dto.UserResponseDTO;
import com.example.hms.payload.dto.UserSummaryDTO;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface UserService {
    UserResponseDTO createUser(UserRequestDTO userRequestDTO);

    UserResponseDTO getUserById(UUID id);

    UserResponseDTO updateUser(UUID id, UpdateUserRequestDTO dto);

    void deleteUser(UUID id);

    void restoreUser(UUID id);

    Page<UserSummaryDTO> getAllUsers(int page, int size);

    Page<UserSummaryDTO> searchUsers(String name, String role, String email, int page, int size);

    boolean verifyEmail(String email, String token);

    // ✅ Admin/staff/hospital-specific registration
    UserResponseDTO createUserWithRolesAndHospital(@Valid AdminSignupRequest adminSignupRequest);

    // ✅ Bootstrap first user (Super Admin) when system has no users
    BootstrapSignupResponse bootstrapFirstUser(BootstrapSignupRequest request);

    // Profile image management
    UUID getUserIdByUsername(String username);

    void updateProfileImage(UUID userId, String imageUrl);

    /**
     * Change a user's own password (self-service).
     * Clears {@code forcePasswordChange} and all rotation-related timestamps.
     *
     * @param userId      the user whose password is being changed
     * @param newPassword the new plaintext password (must be ≥ 8 chars, already validated by caller)
     */
    void changeOwnPassword(UUID userId, String newPassword);

    /**
     * Change a user's own username (self-service, typically on first login).
     * Clears {@code forceUsernameChange}.
     *
     * @param userId      the user whose username is being changed
     * @param newUsername  the desired new username (must be unique, already validated by caller)
     */
    void changeOwnUsername(UUID userId, String newUsername);
}
