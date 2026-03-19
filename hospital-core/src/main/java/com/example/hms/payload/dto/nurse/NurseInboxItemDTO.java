package com.example.hms.payload.dto.nurse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One item in the nurse communication inbox (MVP 13).
 * <p>
 * Backed by the {@code security.notifications} table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NurseInboxItemDTO {

    private UUID id;
    private String message;
    private boolean read;
    private LocalDateTime createdAt;
}
