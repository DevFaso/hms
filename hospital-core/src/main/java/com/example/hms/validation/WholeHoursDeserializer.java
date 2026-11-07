package com.example.hms.validation;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

import java.math.BigDecimal;

/**
 * QA D1433 (round 34): a JSON number with a fraction bound to an {@code Integer}
 * is cut down by Jackson (1.5 and 1.9 become 1), so a PRN interval of 1.5 h was
 * stored as 1 h: a shorter interval than the prescriber wrote. A number that is
 * not whole reads as {@link #NOT_WHOLE}, which the field's own {@code @Min}
 * refuses with its "a whole number of hours, 1 to 72" message on the field. 2.0
 * is 2; anything Jackson cannot read as a number is refused as before.
 */
public class WholeHoursDeserializer extends StdDeserializer<Integer> {

    /** Below the field's minimum: the field's own constraint answers. */
    private static final int NOT_WHOLE = 0;

    public WholeHoursDeserializer() {
        super(Integer.class);
    }

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_NUMBER_FLOAT) {
            return wholeOrRefused(parser.getDecimalValue());
        }
        if (token == JsonToken.VALUE_STRING) {
            return fromText(parser.getString(), context);
        }
        return context.readValue(parser, Integer.class);
    }

    private static Integer wholeOrRefused(BigDecimal value) {
        boolean whole = value.stripTrailingZeros().scale() <= 0;
        return whole ? Integer.valueOf(value.intValue()) : Integer.valueOf(NOT_WHOLE);
    }

    private Integer fromText(String text, DeserializationContext context) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return wholeOrRefused(new BigDecimal(trimmed));
        } catch (NumberFormatException notANumber) {
            return (Integer) context.handleWeirdStringValue(Integer.class, trimmed, "not a number of hours");
        }
    }
}
