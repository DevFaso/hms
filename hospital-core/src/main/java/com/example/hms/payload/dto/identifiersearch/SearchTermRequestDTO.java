package com.example.hms.payload.dto.identifiersearch;

import com.example.hms.validation.RejectsUnknownFields;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The typed term of a list's search box ({@code POST …/term-search}), in the body rather than the
 * URL: what a user types there is often a patient's or a colleague's name, a phone number, an
 * e-mail address or an MRN. The list's other filters (ids, dates, statuses, paging) stay query
 * parameters: they identify no one.
 *
 * <p>The term is required: without one the list itself is the plain GET, so an empty or misspelt
 * body is refused rather than answered with every row (QA D3753).
 */
@RejectsUnknownFields(RejectsUnknownFields.Kind.SEARCH)
public record SearchTermRequestDTO(@NotBlank @Size(max = MAX_LENGTH) String search) {

    /** The longest term a search box sends. */
    public static final int MAX_LENGTH = 200;

    /**
     * The term is read without its surrounding whitespace (any Unicode space), so a term of spaces
     * alone is empty and refused by {@code @NotBlank}.
     */
    public SearchTermRequestDTO {
        search = search == null ? null : search.strip();
    }

    /** The typed term, stripped. */
    public String term() {
        return search;
    }

    /** Never prints a value: a search's terms identify people. */
    @Override
    public String toString() {
        return "SearchTermRequestDTO[search=" + Redacted.presence(search) + "]";
    }
}
