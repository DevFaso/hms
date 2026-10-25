package com.example.hms.service;

import java.util.Locale;
import java.util.UUID;

public interface PasswordResetService {
    // current methods (work as-is)
    void requestReset(String email, Locale locale);
    void confirmReset(String token, String newPassword);

    // optional hardening/ops helpers
    void requestReset(String email, Locale locale, String requestIp); // store IP with token (if desired)
    /**
     * QA D171: a phone-only account asks for its reset link by SMS. Silent for an
     * unknown number, and nothing is issued when no SMS transport delivers for real.
     */
    void requestResetByPhone(String phone, Locale locale, String requestIp);
    /**
     * QA D787: the forgot-password dialog takes whatever the account holder
     * remembers: an e-mail address, a username or a phone number. The link
     * goes to the account's e-mail address when it has one, else by SMS when
     * a real SMS transport is configured; otherwise nothing is issued (the
     * dialog sends the holder to the reception desk). Silent whatever happens:
     * the caller cannot tell whether the account exists or how it was reached.
     */
    void requestResetByIdentifier(String identifier, Locale locale, String requestIp);
    boolean verifyToken(String token);           // hash + check exists, not expired, not consumed
    int cleanupExpiredTokens();                  // delete tokens past expiration
    void invalidateAllForUser(UUID userId);      // revoke all active tokens for a user
}

