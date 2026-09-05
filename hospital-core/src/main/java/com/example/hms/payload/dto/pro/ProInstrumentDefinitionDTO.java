package com.example.hms.payload.dto.pro;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * The complete definition of an instrument — structure, option scores and
 * per-language text — as loaded from its validated source (Tier 2 item 47).
 * This is the import contract ({@code PUT /pro-instruments/{code}}), the
 * durable way content reaches the product: the person holding the
 * validated text loads it; nobody types it into code.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProInstrumentDefinitionDTO {

    @NotBlank
    @Size(max = 40)
    private String code;

    @NotBlank
    @Size(max = 160)
    private String name;

    @Size(max = 40)
    private String version;

    /** Required: content without attribution is content from nowhere. */
    @NotBlank
    @Size(max = 500)
    private String sourceCitation;

    @Size(max = 500)
    private String licenceNote;

    @NotNull
    @Min(0)
    private Integer positiveThreshold;

    /** Item whose non-zero score escalates regardless of total; omit when none. */
    @Min(1)
    private Integer criticalItemNo;

    @Builder.Default
    private boolean active = true;

    @NotEmpty
    @Valid
    private List<Item> items;

    @NotEmpty
    @Valid
    private List<Translation> texts;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Item {
        @NotNull
        @Min(1)
        private Integer itemNo;

        @NotEmpty
        @Valid
        private List<Option> options;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Option {
        @NotNull
        @Min(1)
        private Integer optionNo;

        @NotNull
        @Min(0)
        @Max(100)
        private Integer score;
    }

    /** All text for one language. Option labels are positional: index 0 is option 1. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Translation {
        @NotBlank
        @Size(max = 8)
        private String language;

        /** The instrument's own instruction to the respondent. */
        @Size(max = 4000)
        private String instruction;

        @NotEmpty
        @Valid
        private List<ItemText> items;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ItemText {
        @NotNull
        @Min(1)
        private Integer itemNo;

        @NotBlank
        @Size(max = 4000)
        private String prompt;

        @NotEmpty
        private List<@NotBlank @Size(max = 1000) String> options;
    }
}
