package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnnouncementResponseDTO {
    private UUID id;
    private String text;
    private LocalDateTime date;
    /**
     * A platform announcement (no hospital): listed at every hospital, written and
     * changed only by a platform administrator in global view.
     */
    private boolean platformWide;
}
