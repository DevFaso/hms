package com.example.hms.payload.dto.superadmin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmergencyForceMfaRequestDTO {

    /** Optional. When null/empty, every user with an enrolment is reset. */
    private List<UUID> userIds;

    @NotBlank
    @Size(min = 5, max = 1000)
    private String reason;
}
