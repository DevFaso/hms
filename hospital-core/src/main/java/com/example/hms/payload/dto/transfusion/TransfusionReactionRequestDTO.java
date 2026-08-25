package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.TransfusionReactionSeverity;
import com.example.hms.enums.TransfusionReactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Reporting an adverse reaction. Recording one STOPS the administration. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransfusionReactionRequestDTO {

    @NotNull
    private TransfusionReactionType reactionType;

    @NotNull
    private TransfusionReactionSeverity severity;

    @NotNull
    private LocalDateTime onsetAt;

    @NotBlank
    @Size(max = 1000)
    private String signsSymptoms;

    @Size(max = 1000)
    private String actionsTaken;

    private Boolean unitReturnedToLab;
}
