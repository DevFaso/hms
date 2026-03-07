package com.example.hms.security;

import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements HospitalUserDetailsService {

    private final UserRepository userRepository;
    private final UserRoleHospitalAssignmentRepository userRoleHospitalAssignmentRepository;

    @Override
    @Transactional
    public HospitalUserDetails loadUserByUsername(String username) {
        log.info("🔍 Attempting to load user by username: {}", username);

        User user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> {
                    log.warn("❌ User not found with username: {}", username);
                    return new UsernameNotFoundException("User not found with username: " + username);
                });

        log.info("✅ Loaded user: {}, Active: {}", user.getUsername(), user.isActive());

        // Hospital-scoped roles from user_role_hospital_assignment (active only)
        Set<String> scopedRoles = userRoleHospitalAssignmentRepository.findByUser(user).stream()
            .filter(assignment -> Boolean.TRUE.equals(assignment.getActive()))
            .map(assignment -> assignment.getRole().getCode())
            .collect(Collectors.toSet());

        // Global roles from user_roles (fallback for users without hospital assignments)
        Set<String> globalRoles = user.getUserRoles().stream()
            .map(UserRole::getRole)
            .filter(role -> role != null && role.getCode() != null)
            .map(role -> role.getCode())
            .collect(Collectors.toSet());

        // Merge both sources — hospital-scoped assignments take priority but global roles
        // ensure users always have their base role even without an active hospital assignment
        Set<SimpleGrantedAuthority> authorities = Stream.concat(scopedRoles.stream(), globalRoles.stream())
            .distinct()
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toSet());

        log.info("🔐 User '{}' granted {} authorities (scoped={}, global={}): {}",
                user.getUsername(), authorities.size(), scopedRoles.size(), globalRoles.size(), authorities);

        return new CustomUserDetails(user, authorities);
    }
}

