package com.example.hms.config;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;

import java.math.BigDecimal;

/**
 * QA D657: Jackson truncates a JSON decimal into an {@code Integer} by default,
 * so an invoice line of 1.5 units was stored as 1 with no error. On a field
 * that counts whole things, a fraction is refused (400 naming the field);
 * a decimal with no fractional part (2.0) is still read as 2.
 */
public class WholeIntegerDeserializer extends StdDeserializer<Integer> {

    public WholeIntegerDeserializer() {
        super(Integer.class);
    }

    @Override
    public Integer deserialize(JsonParser p, DeserializationContext ctxt) {
        if (p.currentToken() != JsonToken.VALUE_NUMBER_FLOAT) {
            return ctxt.readValue(p, Integer.class);
        }
        BigDecimal value = p.getDecimalValue();
        try {
            return value.stripTrailingZeros().intValueExact();
        } catch (ArithmeticException notWhole) {
            throw InvalidFormatException.from(p, "Not a whole number", value, Integer.class);
        }
    }
}
