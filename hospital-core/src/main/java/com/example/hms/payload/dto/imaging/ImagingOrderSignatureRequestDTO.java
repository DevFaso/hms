package com.example.hms.payload.dto.imaging;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Payload capturing ordering provider attestation for an imaging order.")
public class ImagingOrderSignatureRequestDTO {

    @NotBlank
    @Size(max = 200)
    private String providerName;

    @Size(max = 40)
    private String providerNpi;

    @NotNull
    private UUID providerUserId;

    @NotBlank
    @Size(max = 1000)
    private String signatureStatement;

    private LocalDateTime signedAt;

    @Schema(description = "Set true when provider confirms electronic attestation language")
    private Boolean attestationConfirmed;
}
