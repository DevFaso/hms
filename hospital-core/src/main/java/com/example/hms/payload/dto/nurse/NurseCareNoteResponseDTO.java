package com.example.hms.payload.dto.nurse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO returned after a quick care note is created (MVP 13).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NurseCareNoteResponseDTO {

    private UUID noteId;
    private UUID patientId;
    private String patientName;
    private String template;
    private String title;
    private String summary;
    private String authorName;
    private LocalDateTime documentedAt;
}
