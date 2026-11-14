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
    /** QA r46: the birth was recorded as before her arrival here (in transit), with its reason. */
    private boolean bornBeforeArrival;
    private String bornBeforeArrivalReason;
    private List<LaborAlertDTO> alerts;

    /** QA D1592: the babies this delivery registered as patients (empty for a stillbirth). */
    private List<DeliveryNewbornDTO> newborns;
    /** QA D1714: each baby's outcome, in birth order, with the fetal-death event of a stillborn one. */
    private List<DeliveryBabyDTO> babies;
    /** QA D1714: every fetal-death event of this delivery, superseded ones included, with their reasons. */
    private List<com.example.hms.payload.dto.mortality.FetalDeathEventDTO> fetalDeathEvents;

    /** QA D1592: the babies are not registered yet (registration failed or still to run): reception registers them. */
    private boolean newbornRegistrationPending;
}
