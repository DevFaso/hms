package com.example.hms.payload.dto.roi;

import com.example.hms.enums.RoiRequesterType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** Log a release-of-information request received at the desk (Tier 2 item 39b). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoiRequestCreateDTO {

    @NotNull(message = "requesterType is required")
    private RoiRequesterType requesterType;

    /** The third party's name; optional for the patient's own request. */
    @Size(max = 200)
    private String requesterName;

    /** Where the copy goes / how to reach the requester. */
    @Size(max = 500)
    private String requesterContact;

    @NotBlank(message = "A purpose is required — it is what the decision weighs.")
    @Size(max = 500)
    private String purpose;

    @NotBlank(message = "The requested scope is required (e.g. \"full record\", \"labs 2025\").")
    @Size(max = 500)
    private String scopeDescription;

    /** Optional — defaults to today; may predate the row for paper requests. */
    private LocalDate requestedOn;
}
