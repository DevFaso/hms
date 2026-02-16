package com.example.hms.payload.dto.superadmin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuperAdminUserForcePasswordResetResponseDTO {

    private int requested;
    private int succeeded;

    @Builder.Default
    private List<SuperAdminUserResetResultDTO> results = new ArrayList<>();
}
