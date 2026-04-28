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
        String uuid
    ) {
        public enum Indicator { info, warning, critical }
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
