package com.example.hms.payload.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Creates or updates a guarantor for a patient (P3 #21). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuarantorRequestDTO {

    @NotBlank
    @Size(max = 200)
    private String fullName;

    @Size(max = 50)
    private String relationship;

    @Size(max = 20)
    private String phone;

    @Email
    @Size(max = 150)
    private String email;

    @Size(max = 255)
    private String address;

    private Boolean primary;

    @Size(max = 500)
    private String notes;
}
