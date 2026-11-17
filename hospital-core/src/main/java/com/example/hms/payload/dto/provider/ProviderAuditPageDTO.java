package com.example.hms.payload.dto.provider;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** One page of a provider facility's own audit trail, newest first. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A page of the caller's provider facility audit trail, newest first.")
public class ProviderAuditPageDTO {

    private List<ProviderAuditEntryDTO> entries;

    /** Zero-based page number. */
    private int page;

    private int size;

    private long totalElements;

    private boolean hasMore;
}
