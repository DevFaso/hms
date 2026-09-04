package com.example.hms.payload.dto.roi;

import com.example.hms.enums.RoiRequestStatus;
import com.example.hms.enums.RoiRequesterType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** One release-of-information request, for the worklist, the chart and the /me view alike. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoiRequestResponseDTO {

    private UUID id;
    private UUID patientId;
    private String patientName;
    private String hospitalName;
    private RoiRequesterType requesterType;
    private String requesterName;
    private String requesterContact;
    private String purpose;
    private String scopeDescription;
    private RoiRequestStatus status;
    private LocalDate requestedOn;
    private LocalDateTime decidedAt;
    private String decidedByName;
    private String decisionNote;
}
