package com.example.hms.payload.dto.pro;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Acknowledging a self-harm-positive response: what was done about it. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProAcknowledgeRequestDTO {

    @Size(max = 4000)
    private String actionTaken;
}
