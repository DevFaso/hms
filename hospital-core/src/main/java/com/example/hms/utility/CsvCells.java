package com.example.hms.utility;

/**
 * CSV injection (billing-money plan PR 4): a cell that a spreadsheet would run as a formula. Every CSV a person opens
 * in Excel or LibreOffice writes its text cells through {@link #safe}; an import refuses them with {@link #isFormula}.
 * {@code CsvFormulaInjectionGuardTest} holds every CSV writer of the code base to it (with a shrinking list of the
 * writers that predate it).
 */
public final class CsvCells {

    private CsvCells() {
    }

    /** Starts with {@code = + - @}, a tab or a carriage return: a spreadsheet would run it. */
    public static boolean isFormula(String cell) {
        if (cell == null || cell.isEmpty()) {
            return false;
        }
        char first = cell.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }

    /** The cell as a spreadsheet will show it and never run it: a formula-like value behind an apostrophe. */
    public static String safe(String cell) {
        if (cell == null) {
            return "";
        }
        return isFormula(cell) ? "'" + cell : cell;
    }
}
