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
public class SuperAdminUserBulkImportResponseDTO {

    private int processed;
    private int imported;
    private int failed;

    @Builder.Default
    private List<SuperAdminUserImportResultDTO> results = new ArrayList<>();
}
