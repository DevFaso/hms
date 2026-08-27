package com.example.hms.payload.dto.portal;

import com.example.hms.enums.DisclosureCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * A patient's accounting of disclosures over a window (Tier 2 item 39).
 *
 * <p>Deliberately a summary <b>plus</b> a page of entries rather than a bare
 * list. Routine treatment access outnumbers everything else by orders of
 * magnitude, so a list sorted by date alone buries the two or three rows a
 * patient actually opened the page to find — an emergency override, a
 * release to another hospital — under months of ordinary chart opens. The
 * counts let the surface lead with those.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Accounting of disclosures for one patient over a date window.")
public class DisclosureAccountingDTO {

    @Schema(description = "Start of the window, or null for no lower bound.")
    private LocalDateTime from;

    @Schema(description = "End of the window, or null for no upper bound.")
    private LocalDateTime to;

    /**
     * Counts per category across the whole window — not just the returned
     * page. Computed by a grouped query so the number is right regardless of
     * how deep the caller pages.
     */
    @Schema(description = "Number of events in each category across the entire window.")
    private Map<DisclosureCategory, Long> countsByCategory;

    @Schema(description = "Total events in the window across all categories.")
    private long totalEvents;

    /**
     * Events that left the treating team — another hospital, an insurer, or
     * a file. The subset a formal accounting of disclosures is actually
     * about; internal treatment access is listed for transparency but is not
     * what the question usually means.
     */
    @Schema(description = "How many of the total went outside the treating team.")
    private long externalDisclosures;

    @Schema(description = "The events themselves, newest first.")
    private List<AccessLogEntryDTO> entries;

    @Schema(description = "Total pages available for the entry list.")
    private int totalPages;

    @Schema(description = "Zero-based index of the returned page.")
    private int page;
}
