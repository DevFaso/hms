package com.example.hms.payload.dto.procedure;

import com.example.hms.enums.ProcedureOrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcedureOrderUpdateDTO {

    private ProcedureOrderStatus status;

    private LocalDateTime scheduledDatetime;

    private Boolean consentObtained;

    private LocalDateTime consentObtainedAt;

    private String consentObtainedBy;

    private String consentFormLocation;

    private Boolean siteMarked;

    private String cancellationReason;
}
