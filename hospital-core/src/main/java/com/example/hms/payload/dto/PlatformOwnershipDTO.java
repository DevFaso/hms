package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformOwnershipDTO {

    private String ownerTeam;
    private String ownerContactEmail;
    private String dataSteward;
    private String serviceLevel;
}
