package com.example.hms.payload.dto;

import com.example.hms.enums.PatientStayStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.UUID;

/**
 * A DTO for patient hospital registration requests.
 * <p>
 * This class is used to transfer data between the client and server
 * for patient registration at a hospital. It includes fields for
 * the patient's username, the hospital's name, and the active status.
 * The MRI field has been removed as it is generated server-side.
 * </p>
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PatientHospitalRegistrationRequestDTO {

    // Preferred human-friendly identifiers
    private String patientUsername;
    private String hospitalName;

    // Backward compatibility (appointments or legacy callers)
    private UUID patientId;
    private UUID hospitalId;

    @Builder.Default
    private boolean active = true; // Used for update/patch; ignored during initial registration.

    private String currentRoom;
    private String currentBed;
    private String attendingPhysicianName;
    private PatientStayStatus stayStatus;
    private String readyForDischargeNote;
}


