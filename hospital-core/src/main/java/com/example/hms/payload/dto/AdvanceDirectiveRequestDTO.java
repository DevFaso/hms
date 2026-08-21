package com.example.hms.payload.dto;

import com.example.hms.enums.AdvanceDirectiveStatus;
import com.example.hms.enums.AdvanceDirectiveType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Write side of an advance directive (P2 #13).
 *
 * <p>The entity, repository, mapper and response DTO all existed; the record was
 * read by the storyboard and by record-sharing and written by NOTHING. A DNR
 * that cannot be entered is a DNR that does not exist.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Create or update an advance directive")
public class AdvanceDirectiveRequestDTO {

    @Schema(description = "Hospital the directive is recorded at; defaults to the caller's")
    private UUID hospitalId;

    @NotNull(message = "Directive type is required.")
    private AdvanceDirectiveType directiveType;

    /** Defaults to ACTIVE when omitted — a newly recorded directive is in force. */
    private AdvanceDirectiveStatus status;

    @Size(max = 2000)
    private String description;

    private LocalDate effectiveDate;

    private LocalDate expirationDate;

    @Size(max = 255)
    private String witnessName;

    @Size(max = 255)
    private String physicianName;

    @Size(max = 255)
    private String documentLocation;

    @Size(max = 100)
    private String sourceSystem;
}
