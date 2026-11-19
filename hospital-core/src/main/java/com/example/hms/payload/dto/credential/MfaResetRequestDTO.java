package com.example.hms.payload.dto.credential;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * QA r64 (lost phone): why an administrator resets a colleague's two-step verification. Required, 5 to 500
 * characters; the service names the field when it is missing ({@code MfaResetService}).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MfaResetRequestDTO {

    private String reason;
}
