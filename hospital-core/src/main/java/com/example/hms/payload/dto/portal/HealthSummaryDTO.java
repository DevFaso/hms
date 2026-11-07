package com.example.hms.payload.dto.portal;

import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.payload.dto.lab.PatientLabResultResponseDTO;
import com.example.hms.payload.dto.medication.PatientMedicationResponseDTO;
import com.example.hms.payload.dto.medicalhistory.ImmunizationResponseDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Aggregated health summary — the "MyChart home screen" equivalent.
 * Combines the most important clinical snapshots into one response.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HealthSummaryDTO {

    private PatientProfileDTO profile;

    private List<PatientLabResultResponseDTO> recentLabResults;

    private List<PatientMedicationResponseDTO> currentMedications;

    private List<PatientVitalSignResponseDTO> recentVitals;

    private List<ImmunizationResponseDTO> immunizations;

    private List<String> activeDiagnoses;

    private List<String> allergies;

    private List<String> chronicConditions;

    /**
     * QA D1362 (round 31, the D1347 class): how many CURRENT lab results the
     * patient has in all - the same rows {@code /me/patient/lab-results}
     * pages through - so a card that shows a few can say how many more there
     * are. {@link #recentLabResults} is only the newest few.
     */
    private Long labResultTotal;

    /**
     * QA D1362: how many medications (issued prescriptions, every status) the
     * patient has in all; {@link #currentMedications} holds only the running
     * courses.
     */
    private Long medicationTotal;
}
