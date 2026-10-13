package com.example.hms.payload.dto.provider;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One answer of the provider directory ({@code GET /provider-directory}):
 * at most {@code ProviderDirectoryService.MAX_RESULTS} entries, and whether
 * more matched, so a picker can ask the user to narrow the search instead of
 * silently missing the facility they want.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A page of the provider directory: the entries, and whether more matched.")
public class ProviderDirectoryPageDTO {

    private List<ProviderDirectoryEntryDTO> entries;

    /** More facilities matched than {@code entries} holds: narrow the search. */
    private boolean hasMore;

    public static ProviderDirectoryPageDTO empty() {
        return new ProviderDirectoryPageDTO(List.of(), false);
    }
}
