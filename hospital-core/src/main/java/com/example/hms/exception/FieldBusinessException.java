package com.example.hms.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A business-rule refusal (400) that belongs to one field of the request,
 * such as a birth time earlier than the partograph it ends (QA D806) or a
 * parity above the gravidity (QA D809). The body carries the translated
 * reason both as {@code message} and under {@code fieldErrors.<field>}, the
 * same shape a bean-validation refusal has, so a form can mark the field.
 *
 * <p>QA D1299 / D1300 (round 28): a refusal can name SEVERAL fields at once
 * ({@link RequiredFields}), so an empty body is refused once with every
 * missing field marked, not one field per press.</p>
 *
 * <p>It is a {@link BusinessException}, so every caller that catches those
 * keeps working; only the response body gains the field.</p>
 */
public class FieldBusinessException extends BusinessException {

    private final String field;
    private final transient Map<String, String> fieldErrors;

    public FieldBusinessException(String field, String messageKey, Object... args) {
        super(messageKey, args);
        this.field = field;
        Map<String, String> one = new LinkedHashMap<>();
        if (field != null && getMessage() != null) {
            one.put(field, getMessage());
        }
        this.fieldErrors = Collections.unmodifiableMap(one);
    }

    /** Several fields, each with its translated reason; the message joins them, one sentence each. */
    FieldBusinessException(Map<String, String> reasons) {
        super(GlobalExceptionHandler.joinReasons(reasons.values().stream()));
        this.field = reasons.keySet().iterator().next();
        this.fieldErrors = Collections.unmodifiableMap(new LinkedHashMap<>(reasons));
    }

    /** The request property the refusal is about ("birthDateTime", "obstetricHistory.para"); the first one when several. */
    public String getField() {
        return field;
    }

    /** Every field the refusal names, with its translated reason, in the order checked. */
    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
