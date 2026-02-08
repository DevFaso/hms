package com.example.hms.repository;

import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {
    boolean existsByUserIdAndRoleId(UUID userId, UUID roleId);
}

