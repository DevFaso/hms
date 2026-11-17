package com.example.hms.payload.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * QA D1126 / D1132 (round 20): the optional body of
 * {@code PUT /appointments/{id}/status}. The reason is required by the service
 * when the action cancels the visit or marks it a no-show (blank is refused
 * there, so a missing body is refused too); other actions send no body.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AppointmentStatusChangeRequestDTO {

    @Size(max = 500)
    private String reason;
}
