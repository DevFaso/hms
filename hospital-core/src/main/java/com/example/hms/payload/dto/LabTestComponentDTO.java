package com.example.hms.payload.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** r63 (NFS panels): one analyte of a panel test definition. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabTestComponentDTO {

    @NotBlank(message = "{lab.component.codeRequired}")
    @Size(max = 30)
    private String code;

    @NotBlank(message = "{lab.component.nameRequired}")
    @Size(max = 120)
    private String name;

    @Size(max = 50)
    private String unit;

    @Valid
    @Builder.Default
    private List<LabTestReferenceRangeDTO> referenceRanges = new ArrayList<>();
}
