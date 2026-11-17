package com.example.hms.payload.dto.imaging;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
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

    /** Ignored: the signer's name comes from the session (QA D302). */
    @Size(max = 200)
    private String providerName;

    @Size(max = 40)
    private String providerNpi;

    /** Optional; when present it must be the signed-in user, or the signature is refused. */
    private UUID providerUserId;

    @NotBlank
    @Size(max = 1000)
    private String signatureStatement;

    /** Ignored: the signing time is the server's clock (QA D302). */
    private LocalDateTime signedAt;

    @Schema(description = "Set true when provider confirms electronic attestation language")
    private Boolean attestationConfirmed;
}
