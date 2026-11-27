package com.example.hms.security.audit;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.security.ClientAddress;
import com.example.hms.security.ClientDevice;

/**
 * The audit rows of a session opening and closing: who, when, from which
 * address and on which device. One shape for the password sign-in and the
 * second-factor step, so the audit screens and a person's own activity read
 * them the same way. The audit service files a staff account's rows at its
 * hospital. They carry the account, never a password, a code or a
 * token.
 */
public final class SignInAuditRows {

    private SignInAuditRows() {
    }

    /**
     * A session was opened for {@code account}.
     *
     * @param secondFactor whether a second factor was verified for it
     */
    public static AuditEventRequestDTO signedIn(User account, boolean secondFactor) {
        return row(account, AuditEventType.LOGIN,
            secondFactor ? "Signed in with the password and a second factor" : "Signed in with the password");
    }

    /** The session of {@code account} was closed by its holder. */
    public static AuditEventRequestDTO signedOut(User account) {
        return row(account, AuditEventType.LOGOUT, "Signed out");
    }

    private static AuditEventRequestDTO row(User account, AuditEventType type, String description) {
        return AuditEventRequestDTO.builder()
            .eventType(type)
            .eventDescription(description)
            .userId(account.getId())
            .userName(account.getUsername())
            .resourceId(account.getId() == null ? null : account.getId().toString())
            .entityType("USER")
            .ipAddress(ClientAddress.current())
            .details(ClientDevice.currentDetails())
            .status(AuditStatus.SUCCESS)
            .build();
    }
}
