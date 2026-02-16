package com.example.hms.payload.dto;

import com.example.hms.payload.dto.nurse.NursingNoteResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportResponseDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Aggregate view of a patient's record for physician review.")
public class DoctorPatientRecordDTO {

    private UUID patientId;
    private UUID hospitalId;
    private String patientName;
    private String hospitalMrn;
    private LocalDate dateOfBirth;

    private String accessReason;
    private LocalDateTime generatedAt;

    private PatientResponseDTO patient;
    private List<PatientAllergyResponseDTO> allergies;
    private List<PrescriptionResponseDTO> medications;
    private List<LabResultResponseDTO> labResults;
    private List<UltrasoundOrderResponseDTO> imagingOrders;
    private List<UltrasoundReportResponseDTO> imagingReports;
    private List<NursingNoteResponseDTO> notes;
    @Schema(description = "Reverse-chronological snapshot of recent encounters with sensitivity markings.")
    private List<PatientTimelineEntryDTO> recentEncounters;
    private List<PatientProblemResponseDTO> problems;
    private List<PatientSurgicalHistoryResponseDTO> surgicalHistory;
    private List<AdvanceDirectiveResponseDTO> advanceDirectives;

    private boolean containsSensitiveData;
    private List<String> sensitiveSections;
}
