package com.example.hms.payload.dto.provider;

import com.example.hms.enums.FacilityType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * One verified, active provider facility in the directory hospitals pick
 * from ({@code GET /provider-directory}, provider plan §6.5). The licence
 * number is shown so a hospital adds the right business (T9).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A verified provider facility in the directory.")
public class ProviderDirectoryEntryDTO {

    private UUID id;
    private String name;
    private String city;
    private String phone;
    private String licenceNumber;
    private FacilityType facilityType;
}
