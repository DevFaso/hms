package com.example.hms.payload.dto.portal;

import com.example.hms.enums.ProxyRelationship;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProxyGrantRequestDTO {

    /** Username of the person being granted proxy access */
    @NotBlank
    private String proxyUsername;

    @NotNull
    private ProxyRelationship relationship;

    /** Comma-separated scopes: APPOINTMENTS,LAB_RESULTS,MEDICATIONS,VITALS,BILLING,ALL */
    @NotBlank
    private String permissions;

    private LocalDateTime expiresAt;

    private String notes;
}
