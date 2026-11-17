package com.example.hms.payload.dto;

import java.util.UUID;

/**
 * One row of the hospital directory (QA D36): what a clinician needs to pick
 * a destination hospital for a referral, and nothing more. No address, phone,
 * email or licence: the full record stays with the hospital's own staff and
 * the super-admin.
 */
public record HospitalDirectoryEntryDTO(UUID id, String name, String city) {
}
