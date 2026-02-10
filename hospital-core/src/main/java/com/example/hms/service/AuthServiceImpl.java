package com.example.hms.service;

import com.example.hms.exception.UnauthorizedException;
import com.example.hms.security.CustomUserDetails;
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

        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserDetails userDetails) {
            return userDetails.getUserId();
        }

        throw new UnauthorizedException("Invalid authentication principal type");
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


