package com.example.hms.exception;

/**
 * A business-rule refusal (400) that belongs to one field of the request,
 * such as a birth time earlier than the partograph it ends (QA D806) or a
 * parity above the gravidity (QA D809). The body carries the translated
 * reason both as {@code message} and under {@code fieldErrors.<field>}, the
 * same shape a bean-validation refusal has, so a form can mark the field.
 *
 * <p>It is a {@link BusinessException}, so every caller that catches those
 * keeps working; only the response body gains the field.</p>
 */
public class FieldBusinessException extends BusinessException {

    private final String field;

    public FieldBusinessException(String field, String messageKey, Object... args) {
        super(messageKey, args);
        this.field = field;
    }

    /** The request property the refusal is about ("birthDateTime", "obstetricHistory.para"). */
    public String getField() {
        return field;
    }
}
