package com.example.hms.exception;

/**
 * A query parameter whose value is outside the closed list it filters on
 * ({@code ?status=BOGUS}). QA D1385 (round 32): such a filter answered 200
 * with an empty list, so the desk read "nobody waiting" instead of "this
 * filter is wrong". The handler answers it exactly as an unparsable enum
 * parameter: 400, {@code code=INVALID_PARAMETER}, the parameter under
 * {@code field} and {@code fieldErrors}, named by its translated label.
 * Thrown by {@link com.example.hms.controller.support.QueryParameterValues}.
 */
public class InvalidQueryParameterException extends RuntimeException {

    private final String parameter;

    public InvalidQueryParameterException(String parameter) {
        super("Invalid value for query parameter " + parameter);
        this.parameter = parameter;
    }

    /** The query parameter's name, as the request spells it. */
    public String getParameter() {
        return parameter;
    }
}
