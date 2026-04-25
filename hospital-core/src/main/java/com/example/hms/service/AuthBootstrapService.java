package com.example.hms.service;

import com.example.hms.payload.dto.SessionBootstrapResponseDTO;

/**
 * Resolves authoritative session context from the DB for the current
 * authenticated principal — used by GET /api/auth/session/bootstrap.
 */
public interface AuthBootstrapService {

    /**
     * Builds the session bootstrap payload for the given username.
     *
     * <p>Side-effect: when {@code authSource} is {@code "keycloak"}, the
     * user's {@code lastOidcLoginAt} timestamp is updated.
     *
     * @param username the authenticated principal's username (never null)
     * @return authoritative session context populated from the DB
     */
    SessionBootstrapResponseDTO resolveCurrentSession(String username);
}
