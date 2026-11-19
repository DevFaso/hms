package com.example.hms.payload.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** r63 (NFS panels): the value entered for one component of a panel test. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabResultComponentValueDTO {

    @Size(max = 30)
    private String code;

    @Size(max = 255)
    private String value;
}
