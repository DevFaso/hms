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

        // One rule for every caller that asks "which roles does this user
        // hold" - see EffectiveRoles (active assignments, plus legacy
        // user_roles with no assignment row, never a leftover ROLE_PATIENT on
        // a staff account: QA D74/D132).
        Set<SimpleGrantedAuthority> authorities = EffectiveRoles.codes(user, assignments).stream()
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toSet());

        log.debug("🔐 Granted {} authorities (active={})", authorities.size(), user.isActive());

        return new CustomUserDetails(user, authorities);
    }
}

