package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/** QA D1564: what the nurse station's vitals capture tells the nurse back. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NurseVitalCaptureResponseDTO {

    private UUID vitalSignId;
    private boolean clinicallySignificant;
    /** The newborn warnings on this set; empty for any patient 28 days old or more. */
    private List<NewbornVitalAlertDTO> newbornAlerts;

    /** QA D1890, r54 (provisional): the obstetric severe-blood-pressure warning (pregnancy, or 42 days after a birth); empty when none. */
    private java.util.List<NewbornVitalAlertDTO> postpartumAlerts;

    /** r54 (provisional): the adult severe-blood-pressure warning (WARNING, or URGENT with a sign of organ damage); empty when none. */
    private java.util.List<NewbornVitalAlertDTO> hypertensionAlerts;
}
