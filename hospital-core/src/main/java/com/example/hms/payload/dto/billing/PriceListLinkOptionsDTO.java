package com.example.hms.payload.dto.billing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * Billing-money plan PR 2: what an item of the acting hospital can be linked to, for the "Lier à" picker. Finance
 * roles cannot read the lab configuration, so the lab tests and services come from here.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceListLinkOptionsDTO {

    private List<String> systemKeys;
    private List<String> transfusionProducts;
    private List<String> imagingModalities;
    private List<Option> labTests;
    private List<Option> treatments;

    /** A linkable record: the key to send, its code (if any) and its name. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Option {
        private UUID id;
        private String code;
        private String name;
    }
}
