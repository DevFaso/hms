package com.example.hms.payload.dto.clinical;

import com.example.hms.enums.MedicalCertificateType;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** QA D961: a medical certificate or sick-leave note asked for from a visit. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicalCertificateRequestDTO {

    @NotNull(message = "{medicalCertificate.type.required}")
    private MedicalCertificateType type;

    /** First day off work (sick leave); defaults to the visit day. */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    /** Days off work, the start day included; required for a sick-leave note. */
    @Min(value = 1, message = "{medicalCertificate.days.range}")
    @Max(value = 365, message = "{medicalCertificate.days.range}")
    private Integer days;

    /** The doctor's own words (required for a general certificate). */
    @Size(max = 1000, message = "{medicalCertificate.remarks.tooLong}")
    private String remarks;

    /** The certificate's language: fr, en or es; the patient's language when absent. */
    @Pattern(regexp = "fr|en|es", message = "{medicalCertificate.language.invalid}")
    private String language;
}
