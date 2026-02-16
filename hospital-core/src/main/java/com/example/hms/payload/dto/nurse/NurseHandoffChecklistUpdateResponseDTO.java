package com.example.hms.payload.dto.nurse;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NurseHandoffChecklistUpdateResponseDTO {

    private UUID handoffId;
    private UUID taskId;
    private boolean completed;
    private LocalDateTime completedAt;
}
