package com.example.hms.payload.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA r65 D2764: the one-time activation code of a pending assignment, shown
 * to the registrar who must hand it over in person when the SMS and the
 * e-mail went nowhere.
 *
 * @param assignmentId     the pending assignment
 * @param confirmationCode the code the holder types to activate
 * @param expiresAt        when the code stops working (server clock, as every
 *                         assignment timestamp); null when it was never sent
 */
public record AssignmentActivationCodeDTO(UUID assignmentId, String confirmationCode, LocalDateTime expiresAt) {
}
