package com.example.hms.controller;

import com.example.hms.payload.dto.MessageResponse;
import com.example.hms.payload.dto.NotificationDeliveryStatusDTO;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.service.OwnEmailChangeService;
import com.example.hms.utility.ActivationDeliveryTracker;
import com.example.hms.utility.MessageUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A user changing their own email address, next to {@code /auth/me/change-password}
 * and {@code /auth/me/change-username}: request (current password; a code goes
 * to the new address), then confirm (the code; the change is applied and the
 * old address is told). The rules live in {@link OwnEmailChangeService}.
 *
 * <p>A Keycloak (single sign-on) session is refused on both steps: Keycloak
 * holds the email of record on that path and runs its own password reset.
 *
 * <p>Every refusal, the single sign-on one included, is audited by the
 * service (one FAILURE row, ids only). The request fields are checked there
 * rather than by bean validation, which would answer 400 before any audit
 * row is written. No answer names an address in clear; the delivery report
 * carries it masked, as activation's does.
 */
@RestController
@RequestMapping("/auth/me/change-email")
@RequiredArgsConstructor
public class OwnEmailChangeController {

    private final OwnEmailChangeService ownEmailChangeService;

    @PostMapping
    @WriteAudited(skip = true, reason = "OwnEmailChangeService audits the request and every refusal, ids only")
    @Operation(summary = "Request a change of your own email address",
        description = "Requires the current password. Sends a code to the new address; the email "
            + "changes only when POST /auth/me/change-email/confirm receives it. Five wrong "
            + "passwords lock this endpoint (not sign-in) for 15 minutes; requests are limited "
            + "per account and per address. An address that already has an account is answered "
            + "like any other (its holder is notified instead of sent a code). Not available to "
            + "a single sign-on session, whose email Keycloak manages.")
    @ApiResponse(responseCode = "200", description = "Mail sent (see delivery); the email is unchanged until confirmed")
    @ApiResponse(responseCode = "400", description = "Current password incorrect, endpoint locked, too many "
        + "requests, address invalid or unchanged, or a single sign-on session")
    public ResponseEntity<Object> request(@RequestBody ChangeEmailRequest request) {
        Optional<UUID> userId = selfServiceUserId();
        if (userId.isEmpty()) {
            return notAuthenticated();
        }
        ActivationDeliveryTracker.open();
        try {
            ownEmailChangeService.requestChange(userId.get(), request.currentPassword(), request.newEmail());
            return ResponseEntity.ok(new ChangeEmailResponse(
                MessageUtil.resolve("user.email.change.sent"), ActivationDeliveryTracker.close()));
        } finally {
            ActivationDeliveryTracker.close();
        }
    }

    @PostMapping("/confirm")
    @WriteAudited(skip = true, reason = "OwnEmailChangeService audits the change and every refusal, ids only")
    @Operation(summary = "Confirm a change of your own email address",
        description = "The code sent to the new address. Applies the change and notifies the previous "
            + "address. Five wrong codes cancel the change.")
    @ApiResponse(responseCode = "200", description = "Email address changed; the previous address is notified")
    @ApiResponse(responseCode = "400", description = "Code incorrect, address taken meanwhile, or a single "
        + "sign-on session")
    @ApiResponse(responseCode = "410", description = "No change is waiting: never requested, expired, or "
        + "cancelled by too many wrong codes")
    public ResponseEntity<Object> confirm(@RequestBody ConfirmEmailChangeRequest request) {
        Optional<UUID> userId = selfServiceUserId();
        if (userId.isEmpty()) {
            return notAuthenticated();
        }
        ActivationDeliveryTracker.open();
        try {
            ownEmailChangeService.confirmChange(userId.get(), request.code());
            return ResponseEntity.ok(new ChangeEmailResponse(
                MessageUtil.resolve("user.email.change.done"), ActivationDeliveryTracker.close()));
        } catch (OwnEmailChangeService.PendingChangeGoneException gone) {
            // Nothing is waiting any more (never requested, expired, or cancelled
            // by too many wrong codes): 410, so the client drops its code form.
            return ResponseEntity.status(HttpStatus.GONE).body(new MessageResponse(gone.getMessage()));
        } finally {
            ActivationDeliveryTracker.close();
        }
    }

    /**
     * The HMS user id of a password-login session. A single sign-on session is
     * refused, and audited with the token's HMS id when it carries one; no
     * session at all is empty.
     */
    private Optional<UUID> selfServiceUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            throw ownEmailChangeService.refuseSingleSignOn(appUserIdOf(jwt));
        }
        return authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails details
            ? Optional.ofNullable(details.getUserId())
            : Optional.empty();
    }

    /** The {@code appUserId} claim Keycloak maps from the user attribute, when it is a UUID. */
    private static UUID appUserIdOf(JwtAuthenticationToken jwt) {
        String raw = jwt.getToken().getClaimAsString("appUserId");
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static ResponseEntity<Object> notAuthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse("Not authenticated."));
    }

    /** Request body of step 1; checked, and every refusal audited, by the service. */
    public record ChangeEmailRequest(String currentPassword, String newEmail) {}

    /** Request body of step 2. */
    public record ConfirmEmailChangeRequest(String code) {}

    /** The answer to both steps: a message, and what was mailed where (masked). */
    public record ChangeEmailResponse(String message, List<NotificationDeliveryStatusDTO> delivery) {}
}
