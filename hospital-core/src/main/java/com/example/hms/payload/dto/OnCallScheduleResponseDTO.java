package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OnCallScheduleResponseDTO {

    private UUID id;
    private UUID staffId;
    private String staffName;
    private UUID departmentId;
    private String departmentName;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private String notes;

    /** True when this entry covers the moment the roster was read. */
    private boolean currentlyOnCall;
}
