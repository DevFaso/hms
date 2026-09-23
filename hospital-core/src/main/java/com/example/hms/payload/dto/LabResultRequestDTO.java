package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabResultRequestDTO {

    private UUID id;

    @NotNull
    private UUID labOrderId;

    @NotNull
    private UUID assignmentId;

    @NotNull
    private UUID patientId;

    @NotBlank
    private String resultValue;

    private String resultUnit;

    /**
     * The analyte this result is for (OBX-3 on an HL7 ORU).
     *
     * <p>Optional, and normally set only by the HL7 inbound adapter: an
     * interactive caller is answering one order for one test and has no
     * second analyte to distinguish. It is what tells two results of one
     * panel apart when they happen to share a value and a timestamp.
     */
    private String testCode;

    @NotNull
    private LocalDateTime resultDate;

    private String notes;
}

