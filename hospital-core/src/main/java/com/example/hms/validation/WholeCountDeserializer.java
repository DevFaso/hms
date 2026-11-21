package com.example.hms.validation;

/**
 * QA D2926 (r66): a count that may be zero (the refills a prescriber writes on an order). Jackson cuts a JSON number
 * with a fraction down to its whole part, so 1.5 refills would be stored as 1. A number that is not whole reads as
 * -1 here, which the field's own {@code @Min(0)} refuses on the field.
 */
public class WholeCountDeserializer extends WholeHoursDeserializer {

    public WholeCountDeserializer() {
        super(-1);
    }
}
