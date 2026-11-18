package com.example.hms.utility;

import java.util.List;
import java.util.regex.Pattern;

/**
 * CSV injection (billing-money plan PR 4): a cell that a spreadsheet would run as a formula. Every CSV a person opens
 * in Excel or LibreOffice writes its text cells through {@link #safe} (or {@link #safeAll} for a CSVPrinter record);
 * an import refuses them with {@link #isFormula}, and a text a person types that is exported later (a price-list
 * label) is refused with it too. {@code CsvFormulaInjectionGuardTest} holds every CSV writer of the code base to it.
 */
public final class CsvCells {

    /** A plain number: a leading minus sign is its sign, not a formula ("-5", "-1.5", "-2,50"). */
    private static final Pattern PLAIN_NUMBER = Pattern.compile("-\\d++(?:[.,]\\d++)?+");

    private CsvCells() {
    }

    /**
     * Starts with {@code = + - @}, a tab or a carriage return: a spreadsheet would run it. A negative plain number
     * is a number, not a formula.
     */
    public static boolean isFormula(String cell) {
        if (cell == null || cell.isEmpty()) {
            return false;
        }
        char first = cell.charAt(0);
        if (first == '-') {
            return !PLAIN_NUMBER.matcher(cell).matches();
        }
        return first == '=' || first == '+' || first == '@' || first == '\t' || first == '\r';
    }

    /** The cell as a spreadsheet will show it and never run it: a formula-like value behind an apostrophe. */
    public static String safe(String cell) {
        if (cell == null) {
            return "";
        }
        return isFormula(cell) ? "'" + cell : cell;
    }

    /** Every text value of a record through {@link #safe}; numbers, dates and the like are kept as they are. */
    public static Object[] safeAll(Object... values) {
        Object[] out = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i] instanceof CharSequence text ? safe(text.toString()) : values[i];
        }
        return out;
    }

    /** {@link #safeAll} for a record held in a list (a header). */
    public static Object[] safeAll(List<?> values) {
        return safeAll(values.toArray());
    }

    /**
     * A cell read back from a file this code base wrote: the one apostrophe {@link #safe} put in front of a
     * formula-like value is taken off, so the value compares equal to what was stored. Anything else is unchanged.
     */
    public static String unescape(String cell) {
        if (cell != null && cell.length() > 1 && cell.charAt(0) == '\'' && isFormula(cell.substring(1))) {
            return cell.substring(1);
        }
        return cell;
    }
}
