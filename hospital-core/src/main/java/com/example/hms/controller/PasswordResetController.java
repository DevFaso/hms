package com.example.hms.controller;

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
        // QA round 29 sweep: {} was answered 204 as if an account had been named.
        // Refusing a blank identifier tells an anonymous caller nothing about any account.
        String identifier = dto.effectiveIdentifier();
        if (identifier == null) {
            throw new com.example.hms.exception.FieldBusinessException("identifier", "auth.reset.identifierRequired");
        }
        return handleResetRequest(identifier, locale, request);
    }

    /**
     * The older query-parameter form. QA D787: it answered 400 to a phone
     * number (the parameter was {@code @Email(message = "{validation.email.invalid}")}); it now takes the same
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
            throw new com.example.hms.exception.FieldBusinessException("identifier", "auth.reset.identifierRequired");
        }
        return handleResetRequest(named, locale, request);
    }

    /**
     * QA D2051: 204 only when the password changed. An unknown, expired or
     * spent link is 400 with one localized message for all three (the service
     * refuses it), and a too-short password keeps its own message. The old
     * catch answered 204 to every one of them, so the page said « Mot de passe
     * réinitialisé » for a password that never changed.
     */
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirmReset(@Valid @RequestBody PasswordResetConfirmDTO dto) {
        // dto.getToken() is the RAW token from the link; service hashes and validates internally
        passwordResetService.confirmReset(dto.getToken(), dto.getNewPassword());
        return ResponseEntity.noContent().build(); // 204
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> legacyConfirmReset(@Valid @RequestBody PasswordResetConfirmDTO dto) {
        return confirmReset(dto);
    }

    /** Extract client IP, honoring common proxy headers. */
    private static String clientIp(HttpServletRequest request) {
        return com.example.hms.security.ClientAddress.of(request);
    }

    private ResponseEntity<Void> handleResetRequest(String identifier, Locale locale, HttpServletRequest request) {
        // QA D802: the same constant work for every identifier. The lookup, the
        // token, the send and the audit run on the dispatcher's pool after this
        // answer, so neither the status nor the time says whether the account exists.
        resetRequests.dispatch(identifier, locale, clientIp(request));
        return ResponseEntity.noContent().build(); // 204
    }
}
