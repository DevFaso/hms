package com.example.hms.payload.dto.nurse;

import java.util.UUID;

/** QA D776: a colleague an SBAR handoff can be addressed to, as the picker lists them. */
public record NurseHandoffRecipientDTO(UUID userId, String displayName) {
}
