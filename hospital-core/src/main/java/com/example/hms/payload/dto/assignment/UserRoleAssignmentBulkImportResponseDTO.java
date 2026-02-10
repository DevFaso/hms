package com.example.hms.payload.dto.assignment;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Collections;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserRoleAssignmentBulkImportResponseDTO {

    @Builder.Default
    private int processed = 0;

    @Builder.Default
    private int created = 0;

    @Builder.Default
    private int skipped = 0;

    @Builder.Default
    private int failed = 0;

    @Builder.Default
    private List<UserRoleAssignmentBulkImportResultDTO> results = Collections.emptyList();
}
