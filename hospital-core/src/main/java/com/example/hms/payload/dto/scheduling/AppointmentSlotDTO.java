package com.example.hms.payload.dto.scheduling;

import com.example.hms.enums.SlotStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppointmentSlotDTO {

    private UUID id;
    private UUID staffId;
    private String staffName;
    private UUID departmentId;
    private String departmentName;
    private UUID visitTypeId;
    private String visitTypeName;
    private Integer durationMinutes;
    private LocalDate slotDate;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private SlotStatus status;
    private UUID appointmentId;
}
