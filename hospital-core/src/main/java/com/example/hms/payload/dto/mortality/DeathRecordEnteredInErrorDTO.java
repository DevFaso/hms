package com.example.hms.payload.dto.mortality;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** QA D1488: why a death record is marked entered in error. The service refuses an over-long reason. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeathRecordEnteredInErrorDTO {

    @NotBlank
    private String reason;
}
