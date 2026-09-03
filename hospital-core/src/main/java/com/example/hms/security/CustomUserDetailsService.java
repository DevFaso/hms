package com.example.hms.security;

import com.example.hms.model.User;
import com.example.hms.model.Role;
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
        // This method fires per-request on every authenticated call. INFO-level
        // logging here was both noisy (one line per request times every user) and
        // a steady drip of usernames + role sets into log aggregators. Demoted to
        // DEBUG and stripped of identifying info on the hot path; the lookup
        // failure is the only branch that retains operator-actionable detail
        // (and it's still DEBUG-level without surfacing the username — the
        // upstream JwtAuthenticationFilter already emits a redacted WARN line
        // when authentication ultimately fails).
        log.debug("🔍 loadUserByUsername invoked");

        User user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> {
                    // Subject not found is operator-actionable but not enumerable
                    // (the caller already presented this username via JWT subject
                    // or login form). Logged at DEBUG to avoid PII bleed; the
                    // upstream filter already emits a redacted WARN line.
                    log.debug("❌ User not found for supplied username");
                    return new UsernameNotFoundException("User not found.");
                });

        var assignments = userRoleHospitalAssignmentRepository.findByUser(user);

        // Hospital-scoped roles from user_role_hospital_assignment (active only)
        Set<String> scopedRoles = assignments.stream()
            .filter(assignment -> Boolean.TRUE.equals(assignment.getActive()))
            .map(assignment -> assignment.getRole().getCode())
            .collect(Collectors.toSet());

        // Every role that has ANY assignment row is governed by those rows.
        Set<String> assignmentGovernedRoles = assignments.stream()
            .map(assignment -> assignment.getRole() != null ? assignment.getRole().getCode() : null)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());

        // Global roles from user_roles — a fallback ONLY for roles with no
        // assignment row at all (legacy data predating hospital scoping).
        // assignRole syncs every new role into user_roles immediately, so an
        // unfiltered fallback would resurrect roles whose assignments are
        // still INACTIVE: verifying one assignment (which activates the user)
        // would silently grant every sibling unverified role.
        Set<String> globalRoles = user.getUserRoles().stream()
            .map(UserRole::getRole)
            .filter(role -> role != null && role.getCode() != null)
            .map(Role::getCode)
            .filter(code -> !assignmentGovernedRoles.contains(code))
            .collect(Collectors.toSet());

        // Merge both sources — active hospital-scoped assignments plus the
        // legacy-only fallback above.
        Set<SimpleGrantedAuthority> authorities = Stream.concat(scopedRoles.stream(), globalRoles.stream())
            .distinct()
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toSet());

        log.debug("🔐 Granted {} authorities (scoped={}, global={}, active={})",
                authorities.size(), scopedRoles.size(), globalRoles.size(), user.isActive());

        return new CustomUserDetails(user, authorities);
    }
}

