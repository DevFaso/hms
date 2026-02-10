package com.example.hms.payload.dto.encounter.workspace;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.EncounterWorkspaceVisitType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncounterWorkspaceDetailDTO {
    private UUID id;
    private UUID patientId;
    private String patientName;
    private String patientMrn;
    private String patientDob;
    private EncounterType encounterType;
    private EncounterWorkspaceVisitType visitType;
    private EncounterStatus status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String location;
    private String department;
    private String providerId;
    private String providerName;
    private String chiefComplaint;
    private String historyOfPresentIllness;
    private EncounterWorkspaceNoteDTO structuredNotes;
}
