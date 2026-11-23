package com.example.hms.payload.dto.billing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * QA r70 (lab set-up): what « Lier par code » did to the hospital's unlinked lab price lines. Codes only, never a
 * patient: a price line linked, and the codes left unlinked because no live test has that code, several do, or the
 * test is already linked to another line.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceListAutoLinkReportDTO {

    private List<Linked> linked;
    private List<String> unmatched;
    private List<String> ambiguous;
    private List<String> alreadyTaken;

    /** One price line linked to a lab test. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Linked {
        private UUID itemId;
        private String code;
        private String label;
        private String testCode;
        private String testName;
    }
}
