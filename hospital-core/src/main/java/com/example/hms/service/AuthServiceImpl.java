package com.example.hms.service;

import com.example.hms.exception.UnauthorizedException;
import com.example.hms.security.PrincipalUserIds;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Service
public class AuthServiceImpl implements AuthService {

    @Override
    public UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException("User is not authenticated");
        }

        // The one principal → user id rule (PrincipalUserIds): a password-path
        // principal, or a Keycloak token's appUserId. It used to accept only
        // CustomUserDetails, so every guard built on it refused a Keycloak
        // user with 401 on their own record.
        return PrincipalUserIds.of(authentication)
            .orElseThrow(() -> new UnauthorizedException("Invalid authentication principal type"));
    }

    @Override
    public String getCurrentUserToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException("User is not authenticated");
        }

        Object credentials = authentication.getCredentials();
        if (credentials instanceof String token && StringUtils.hasText(token)) {
            return token;
        }

        if (credentials != null && StringUtils.hasText(credentials.toString())) {
            return credentials.toString();
        }

        throw new UnauthorizedException("JWT token is missing or invalid");
    }

    @Override
    public boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .anyMatch(grantedAuthority -> grantedAuthority.getAuthority().equals(role));
    }
}


