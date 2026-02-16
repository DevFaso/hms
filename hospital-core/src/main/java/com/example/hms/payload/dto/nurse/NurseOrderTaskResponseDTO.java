package com.example.hms.payload.dto.nurse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NurseOrderTaskResponseDTO {

    private UUID id;
    private UUID patientId;
    private String patientName;
    private String orderType;
    private String priority;
    private LocalDateTime dueTime;
}
