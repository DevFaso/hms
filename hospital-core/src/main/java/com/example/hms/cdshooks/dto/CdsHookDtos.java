package com.example.hms.cdshooks.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Data-transfer objects for the
 * <a href="https://cds-hooks.hl7.org/1.0/">CDS Hooks 1.0</a> contract.
 *
 * <p>Records are public so Jackson can serialise them without reflective
 * accessor probes. Optional fields use {@code @JsonInclude(NON_NULL)} on
 * the wrapping records.
 */
public final class CdsHookDtos {

    private CdsHookDtos() {}

    /** Discovery response — {@code GET /cds-services}. */
    public record CdsServiceCatalog(List<CdsServiceDescriptor> services) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CdsServiceDescriptor(
        String hook,
        String id,
        String title,
        String description,
        Map<String, String> prefetch
    ) {}

    /** Hook-invocation request body — {@code POST /cds-services/{id}}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CdsHookRequest(
        String hook,
        String hookInstance,
        String fhirServer,
        String fhirAuthorization,
        String user,
        Map<String, Object> context,
        Map<String, Object> prefetch
    ) {}

    /** Hook response — list of cards the user agent should render. */
    public record CdsHookResponse(List<CdsCard> cards) {
        public static CdsHookResponse empty() {
            return new CdsHookResponse(List.of());
        }
        public static CdsHookResponse of(List<CdsCard> cards) {
            return new CdsHookResponse(cards == null ? List.of() : cards);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CdsCard(
        String summary,
        String detail,
        Indicator indicator,
        Source source,
        List<CdsLink> links,
        List<CdsSuggestion> suggestions,
        String selectionBehavior,
        String uuid,
        /*
         * Round 18 (QA D935): true on a card no override can pass (a dose
         * above the absolute daily maximum). Absent (null) on every other
         * card, so the CDS Hooks wire shape is unchanged for them.
         */
        Boolean hardStop,
        /*
         * Round 19 (QA D1096): true on a card that says a pharmacist or a
         * physician must review the dose (a child under the item's
         * manual-review age, or outside every weight band). The order is then
         * not signed until that review is recorded on it. Absent (null) on
         * every other card, so the CDS Hooks wire shape is unchanged for them.
         */
        Boolean reviewRequired
    ) {
        /** A card any critical-override path may pass with a reason (every card before round 18). */
        public CdsCard(String summary, String detail, Indicator indicator, Source source,
                       List<CdsLink> links, List<CdsSuggestion> suggestions,
                       String selectionBehavior, String uuid) {
            this(summary, detail, indicator, source, links, suggestions, selectionBehavior, uuid, null, null);
        }

        /** A card with the round-18 hard-stop flag and no review requirement. */
        public CdsCard(String summary, String detail, Indicator indicator, Source source,
                       List<CdsLink> links, List<CdsSuggestion> suggestions,
                       String selectionBehavior, String uuid, Boolean hardStop) {
            this(summary, detail, indicator, source, links, suggestions, selectionBehavior, uuid, hardStop, null);
        }

        /** This card with another {@code uuid}; every other field is kept. */
        public CdsCard withUuid(String newUuid) {
            return new CdsCard(summary, detail, indicator, source, links, suggestions, selectionBehavior, newUuid,
                hardStop, reviewRequired);
        }

        /** Whether no override may pass this card. */
        public boolean blocksOverride() {
            return Boolean.TRUE.equals(hardStop);
        }

        /** Whether the order needs a recorded pharmacist or physician review before it is signed (QA D1096). */
        public boolean requiresReview() {
            return Boolean.TRUE.equals(reviewRequired);
        }

        public enum Indicator {
            INFO, WARNING, CRITICAL;

            /** Wire format per CDS Hooks 1.0 — lowercase. */
            @com.fasterxml.jackson.annotation.JsonValue
            public String wire() {
                return name().toLowerCase(java.util.Locale.ROOT);
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(String label, String url, String icon) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CdsLink(String label, String url, String type, String appContext) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CdsSuggestion(
        String label,
        String uuid,
        Boolean isRecommended,
        List<CdsSuggestionAction> actions
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CdsSuggestionAction(String type, String description, Object resource) {}
}
