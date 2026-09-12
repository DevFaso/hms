package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * E9 #64 — what a read withheld under decision D3, per recording hospital
 * and department: enough for the chart to render <i>Dossier restreint
 * (hôpital, département, n)</i> and offer break-the-glass, and nothing
 * more. No row id, no date, no summary — the whole point of the rule is
 * that those stay behind the declaration.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Rows withheld from this read because they were recorded at another hospital "
    + "in a sensitive category; they open through break-the-glass.")
public class RestrictedRowsDTO {

    private UUID hospitalId;
    private String hospitalName;
    /** Null when the rows carry no department (problems, notes). */
    private String departmentName;
    private long count;
}
