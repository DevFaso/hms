package com.example.hms.controller;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.PasswordResetConfirmDTO;
import com.example.hms.payload.dto.PasswordResetRequestDTO;
import com.example.hms.service.PasswordResetRequestDispatcher;
import com.example.hms.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/auth/password")
@RequiredArgsConstructor
@Slf4j
public class PasswordResetController {

    private final PasswordResetService passwordResetService;
    /** QA D802: the account work for a reset request runs off the request thread. */
    private final PasswordResetRequestDispatcher resetRequests;

    /**
     * QA D787 (D171): the holder names the account by e-mail, username or phone
     * ({@code identifier}, or the older {@code email} / {@code phone} fields).
     * Always 204, whatever the account and whatever channel reached it.
     */
    @PostMapping("/request")
    public ResponseEntity<Void> requestReset(
        @Valid @RequestBody PasswordResetRequestDTO dto,
        Locale locale,
        HttpServletRequest request
    ) {
        return handleResetRequest(dto.effectiveIdentifier(), locale, request);
    }

    /**
     * The older query-parameter form. QA D787: it answered 400 to a phone
     * number (the parameter was {@code @Email}); it now takes the same
     * identifiers as {@code /request}, as {@code email}, {@code identifier}
     * or {@code phone}.
     */
    @PostMapping("/request-reset")
    public ResponseEntity<Void> requestResetQuery(
        @RequestParam(value = "email", required = false) String email,
        @RequestParam(value = "identifier", required = false) String identifier,
        @RequestParam(value = "phone", required = false) String phone,
        Locale locale,
        HttpServletRequest request
    ) {
        String named = PasswordResetRequestDTO.builder()
            .email(email).identifier(identifier).phone(phone).build()
            .effectiveIdentifier();
        if (named == null) {
            return ResponseEntity.badRequest().build();
        }
        return handleResetRequest(named, locale, request);
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirmReset(@Valid @RequestBody PasswordResetConfirmDTO dto) {
        // dto.getToken() is the RAW token from the link; service hashes and validates internally
        try {
            passwordResetService.confirmReset(dto.getToken(), dto.getNewPassword());
        } catch (ResourceNotFoundException | IllegalStateException e) {
            // Don’t reveal token validity details to avoid giving attackers useful signals.
            log.debug("Password reset confirm failed: {}", e.getMessage());
            // You can still return 204 to keep response uniform.
        }
        return ResponseEntity.noContent().build(); // 204
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> legacyConfirmReset(@Valid @RequestBody PasswordResetConfirmDTO dto) {
        return confirmReset(dto);
    }

    /** Extract client IP, honoring common proxy headers. */
    private static String clientIp(HttpServletRequest request) {
        String h = request.getHeader("X-Forwarded-For");
        if (h != null && !h.isBlank()) {
            // XFF may contain a list: client, proxy1, proxy2...
            int comma = h.indexOf(',');
            return comma > 0 ? h.substring(0, comma).trim() : h.trim();
        }
        h = request.getHeader("X-Real-IP");
        return (h != null && !h.isBlank()) ? h.trim() : request.getRemoteAddr();
    }

    private ResponseEntity<Void> handleResetRequest(String identifier, Locale locale, HttpServletRequest request) {
        // QA D802: the same constant work for every identifier. The lookup, the
        // token, the send and the audit run on the dispatcher's pool after this
        // answer, so neither the status nor the time says whether the account exists.
        resetRequests.dispatch(identifier, locale, clientIp(request));
        return ResponseEntity.noContent().build(); // 204
    }
}
