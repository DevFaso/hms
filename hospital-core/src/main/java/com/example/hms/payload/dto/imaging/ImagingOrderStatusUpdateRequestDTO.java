package com.example.hms.payload.dto.imaging;

import com.example.hms.enums.ImagingOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for updating imaging order status or scheduling metadata.")
public class ImagingOrderStatusUpdateRequestDTO {

    @NotNull
    @Schema(description = "New order status", requiredMode = Schema.RequiredMode.REQUIRED)
    private ImagingOrderStatus status;

    @Schema(description = "Updated appointment date")
    private LocalDate scheduledDate;

    @Schema(description = "Updated appointment time text")
    private String scheduledTime;

    @Schema(description = "Updated appointment location")
    private String appointmentLocation;

    @Schema(description = "Notes for scheduling/coordination team")
    private String workflowNotes;

    @Schema(description = "Authorization flag update")
    private Boolean requiresAuthorization;

    @Schema(description = "Authorization number if available")
    private String authorizationNumber;

    @Schema(description = "Cancellation reason when status is CANCELLED")
    private String cancellationReason;

    @Schema(description = "User performing the update")
    private UUID performedByUserId;

    @Schema(description = "Display name of the user performing the update")
    private String performedByName;
}
