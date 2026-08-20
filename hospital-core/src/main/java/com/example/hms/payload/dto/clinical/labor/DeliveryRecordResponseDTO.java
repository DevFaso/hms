package com.example.hms.payload.dto.clinical.labor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryRecordResponseDTO {

    private UUID id;
    private UUID episodeId;
    private UUID patientId;
    private String deliveredByStaffName;

    private LocalDateTime birthDateTime;
    private String deliveryMode;
    private boolean liveBirth;
    private int numberOfInfants;
    private String infantSex;
    private Integer birthWeightGrams;
    private Integer gestationalAgeWeeksAtBirth;
    private Integer apgarOneMinute;
    private Integer apgarFiveMinute;

    private LocalDateTime placentaDeliveredAt;
    private Boolean placentaComplete;
    private Boolean uterotonicGiven;
    private Integer estimatedBloodLossMl;
    private String perinealTear;

    private String notes;
    private List<LaborAlertDTO> alerts;
}
