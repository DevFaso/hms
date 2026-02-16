package com.example.hms.service;

import java.util.Locale;
import java.util.UUID;

public interface PasswordResetService {
    // current methods (work as-is)
    void requestReset(String email, Locale locale);
    void confirmReset(String token, String newPassword);

    // optional hardening/ops helpers
    void requestReset(String email, Locale locale, String requestIp); // store IP with token (if desired)
    boolean verifyToken(String token);           // hash + check exists, not expired, not consumed
    int cleanupExpiredTokens();                  // delete tokens past expiration
    void invalidateAllForUser(UUID userId);      // revoke all active tokens for a user
}

