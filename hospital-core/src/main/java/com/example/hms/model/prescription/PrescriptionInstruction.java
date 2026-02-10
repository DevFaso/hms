package com.example.hms.model.prescription;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrescriptionInstruction {

    @Column(name = "label", length = 120)
    private String label;

    @Column(name = "instruction_text", length = 1024)
    private String instructionText;

    @Column(name = "education_url", length = 512)
    private String educationUrl;

    @Column(name = "language_code", length = 10)
    private String languageCode;

    @Builder.Default
    @Column(name = "patient_acknowledged", nullable = false)
    private boolean patientAcknowledged = false;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;
}
