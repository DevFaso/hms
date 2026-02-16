package com.example.hms.security;

import com.example.hms.model.User;
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

        Set<SimpleGrantedAuthority> authorities = userRoleHospitalAssignmentRepository.findByUser(user).stream()
            .filter(assignment -> Boolean.TRUE.equals(assignment.getActive()))
            .map(assignment -> new SimpleGrantedAuthority(assignment.getRole().getCode())) // ✅ FIXED
            .collect(Collectors.toSet());


        log.info("🔐 User '{}' granted {} authorities: {}", user.getUsername(), authorities.size(), authorities);

        return new CustomUserDetails(user, authorities);
    }
}

