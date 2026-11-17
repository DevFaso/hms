package com.example.hms.payload.dto.clinical;

import com.example.hms.enums.UnseenVisitOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** QA D788: {@code POST /encounters/{id}/close-unseen}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloseUnseenVisitRequestDTO {

    @NotNull(message = "{encounter.closeUnseen.outcome.required}")
    private UnseenVisitOutcome outcome;

    @NotBlank(message = "{encounter.closeUnseen.reason.required}")
    @Size(max = 500, message = "{encounter.closeUnseen.reason.size}")
    private String reason;
}
