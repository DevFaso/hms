package com.example.hms.service;

import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.BootstrapSignupRequest;
import com.example.hms.payload.dto.BootstrapSignupResponse;
import com.example.hms.payload.dto.UserRequestDTO;
import com.example.hms.payload.dto.UserResponseDTO;
import com.example.hms.payload.dto.UserSummaryDTO;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface UserService {
    UserResponseDTO createUser(UserRequestDTO userRequestDTO);

    UserResponseDTO getUserById(UUID id);

    UserResponseDTO updateUser(UUID id, UserRequestDTO userRequestDTO);

    void deleteUser(UUID id);

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
}
