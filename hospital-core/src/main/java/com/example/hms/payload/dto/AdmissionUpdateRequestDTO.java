package com.example.hms.payload.dto;

import com.example.hms.enums.AcuityLevel;
import com.example.hms.validation.RejectsUnknownFields;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for updating an existing admission.
 *
 * <p>QA D1672 (r44): the edit dialog sent the chief complaint, type, time and
 * admitting clinician, none of which this request took; the PUT answered 200
 * and kept them. The chief complaint is now taken. The patient, hospital,
 * type, time and admitting clinician record the admission itself and are
 * read-only in the dialog; a body that sends them, or any other property this
 * class does not declare, is refused naming it ({@link RejectsUnknownFields}).
 */
@RejectsUnknownFields
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdmissionUpdateRequestDTO {

    private UUID departmentId;
    @jakarta.validation.constraints.Size(max = 50, message = "{admission.roomBed.size}")
    private String roomBed;
    private AcuityLevel acuityLevel;
    private LocalDateTime expectedDischargeDateTime;
    @jakarta.validation.constraints.Size(max = 2000, message = "{admission.admissionNotes.size}")
    private String admissionNotes;
    private UUID attendingPhysicianId;

    /** QA D1672: a corrected chief complaint; when sent, it is not blank. */
    @jakarta.validation.constraints.Size(max = 500, message = "{admission.chiefComplaint.size}")
    @jakarta.validation.constraints.Pattern(regexp = "(?s).*\\S.*", message = "{admission.chiefComplaint.required}")
    private String chiefComplaint;

    /**
     * Add secondary diagnoses
     */
    private List<Map<String, String>> secondaryDiagnosesToAdd;
    
    private Map<String, Object> metadata;
}
