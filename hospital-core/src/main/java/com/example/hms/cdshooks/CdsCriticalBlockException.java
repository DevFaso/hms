package com.example.hms.cdshooks;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.exception.BusinessException;

import java.util.List;

/**
 * Thrown by services that gate writes on a CDS rule-engine result and
 * surface the structured {@link CdsCard cards} to the API caller. The
 * {@link com.example.hms.exception.GlobalExceptionHandler} maps this to
 * a 400 with body {@code { message, cdsAdvisories }} so the UI can
 * render the cards directly instead of parsing the message text.
 *
 * <p>Subclasses {@link BusinessException} so existing code paths (and
 * tests) that catch BusinessException keep working — only the wire
 * shape of the response changes.
 */
public class CdsCriticalBlockException extends BusinessException {

    private final transient List<CdsCard> cards;

    /** The request field the refusal is about, or {@code null}: the body then names it under {@code fieldErrors}. */
    private final String field;

    public CdsCriticalBlockException(String message, List<CdsCard> cards) {
        this(message, cards, null);
    }

    /** A refusal about one request field (Policy 3: {@code allergyOverrideReason} at the signature). */
    public CdsCriticalBlockException(String message, List<CdsCard> cards, String field) {
        super(message);
        this.field = field;
        this.cards = cards == null ? List.of() : List.copyOf(cards);
    }

    public List<CdsCard> getCards() {
        return cards;
    }

    public String getField() {
        return field;
    }
}
