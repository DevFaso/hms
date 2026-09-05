package com.example.hms.payload.dto.pro;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * An instrument rendered in one language, ready to administer. Carries NO
 * option scores: the respondent (or the clinician reading the items aloud)
 * should not see how each answer weighs — that is the instrument's whole
 * design, and it is the same reason the score is computed server-side.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProInstrumentViewDTO {

    private String code;
    private String name;
    private String version;
    private String sourceCitation;
    private String licenceNote;
    /** Language actually served — may differ from the one asked for when it has no text. */
    private String language;
    private List<String> availableLanguages;
    private String instruction;
    private int maxScore;
    private Integer criticalItemNo;
    private List<Item> items;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Item {
        private int itemNo;
        private String prompt;
        private List<Option> options;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Option {
        private int optionNo;
        private String label;
    }
}
