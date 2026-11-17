package com.example.hms.service.billing;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.utility.CsvCells;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Billing-money plan PR 4: the price-list CSV file format, with no I/O and no database, so each rule is unit tested
 * on its own.
 *
 * <ul>
 *   <li>UTF-8 (a BOM is accepted, README A-10), or Windows-1252 when the bytes are not valid UTF-8 (French Excel's
 *       "CSV (séparateur : point-virgule)").</li>
 *   <li>Separator {@code ;} or {@code ,}, read from the header line (French Excel writes {@code ;}).</li>
 *   <li>Header {@code code;libelle;categorie;prix;date_effet;liaison_type;liaison_cle}, in any order, any case,
 *       accents ignored; the last three columns may be left out. An unknown or repeated column refuses the file.</li>
 *   <li>{@code prix} in whole francs; spaces and non-breaking spaces group the thousands ("2 000"); ",00" is
 *       accepted, any other decimal is refused.</li>
 *   <li>{@code date_effet} as {@code JJ/MM/AAAA} or ISO {@code AAAA-MM-JJ}; blank means today.</li>
 *   <li>At most {@link #MAX_ROWS} rows and {@link #MAX_BYTES} bytes.</li>
 *   <li>A cell that a spreadsheet would run as a formula ({@code = + - @}, tab, carriage return first) is refused at
 *       import ({@link #isFormula}) and escaped with a leading apostrophe on export ({@link #write}).</li>
 * </ul>
 *
 * <p>A file-level refusal names the field {@code file}; a cell-level one names the CSV column.
 */
public final class PriceListCsv {

    public static final int MAX_ROWS = 5_000;
    public static final int MAX_BYTES = 1_048_576;

    public static final String FIELD_FILE = "file";
    public static final String COL_CODE = "code";
    public static final String COL_LABEL = "libelle";
    public static final String COL_CATEGORY = "categorie";
    public static final String COL_PRICE = "prix";
    public static final String COL_DATE = "date_effet";
    public static final String COL_LINK_TYPE = "liaison_type";
    public static final String COL_LINK_KEY = "liaison_cle";

    /** The header, in the order the export writes it and the template shows it. */
    public static final List<String> HEADER = List.of(COL_CODE, COL_LABEL, COL_CATEGORY, COL_PRICE, COL_DATE,
        COL_LINK_TYPE, COL_LINK_KEY);
    private static final List<String> REQUIRED = List.of(COL_CODE, COL_LABEL, COL_CATEGORY, COL_PRICE);

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    /**
     * The file's date cell, JJ/MM/AAAA (one-digit day and month accepted). A wire format fixed by the plan for every
     * reader, not a display: built field by field, so no locale reorders it.
     */
    private static final DateTimeFormatter FR_DATE = new DateTimeFormatterBuilder()
        .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE).appendLiteral('/')
        .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NOT_NEGATIVE).appendLiteral('/')
        .appendValue(ChronoField.YEAR, 4)
        .toFormatter().withChronology(IsoChronology.INSTANCE).withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter FR_DATE_OUT = new DateTimeFormatterBuilder()
        .appendValue(ChronoField.DAY_OF_MONTH, 2).appendLiteral('/')
        .appendValue(ChronoField.MONTH_OF_YEAR, 2).appendLiteral('/')
        .appendValue(ChronoField.YEAR, 4)
        .toFormatter();
    /** Digits, then at most one decimal part after a comma or a dot (thousand spaces already removed). */
    private static final Pattern NUMBER = Pattern.compile("(-?)(\\d{1,15})(?:[.,](\\d+))?");
    private static final Pattern ZEROS = Pattern.compile("0{1,2}");
    private static final Pattern EDGE_SPACES = Pattern.compile("(?:^[\\s\u00A0\u202F]++)|(?:[\\s\u00A0\u202F]++$)");

    private PriceListCsv() {
    }

    /** One data row as read: every cell trimmed (blank when the column is absent), with its line in the file. */
    public record Row(int line, Map<String, String> cells, boolean extraCells) {
        public String get(String column) {
            return cells.getOrDefault(column, "");
        }
    }

    /** The decoded file's rows (blank rows skipped). */
    public record Parsed(char separator, Charset charset, List<Row> rows) {
    }

    /** One line of the export, already in the import's words. */
    public record ExportRow(String code, String label, String category, BigDecimal price, LocalDate effectiveFrom,
                            String linkType, String linkKey) {
    }

    // ───────────────────────────── reading ─────────────────────────────

    /** The file's rows, or a refusal of the whole file naming {@code file}. */
    public static Parsed parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new FieldBusinessException(FIELD_FILE, "billing.priceList.import.fileEmpty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new FieldBusinessException(FIELD_FILE, "billing.priceList.import.fileTooLarge");
        }
        Charset charset = charsetOf(bytes);
        String text = decode(bytes, charset);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        int firstBreak = text.indexOf('\n');
        String headerLine = firstBreak < 0 ? text : text.substring(0, firstBreak);
        if (headerLine.isBlank()) {
            throw new FieldBusinessException(FIELD_FILE, "billing.priceList.import.fileEmpty");
        }
        char separator = separatorOf(headerLine);
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setDelimiter(separator)
            .setIgnoreEmptyLines(false)
            .setTrim(false)
            .get();
        List<Row> rows;
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            rows = rowsOf(parser.iterator());
        } catch (IOException | UncheckedIOException | IllegalStateException unreadable) {
            throw new FieldBusinessException(FIELD_FILE, "billing.priceList.import.unreadable");
        }
        if (rows.isEmpty()) {
            throw new FieldBusinessException(FIELD_FILE, "billing.priceList.import.noRows");
        }
        return new Parsed(separator, charset, rows);
    }

    /** The header record, then every non-blank data row (at most {@link #MAX_ROWS}). */
    private static List<Row> rowsOf(java.util.Iterator<CSVRecord> records) {
        List<Row> rows = new ArrayList<>();
        if (!records.hasNext()) {
            return rows;
        }
        Map<String, Integer> columns = headerOf(records.next());
        while (records.hasNext()) {
            CSVRecord csvRecord = records.next();
            if (!isBlank(csvRecord)) {
                if (rows.size() >= MAX_ROWS) {
                    throw new FieldBusinessException(FIELD_FILE, "billing.priceList.import.tooManyRows", MAX_ROWS);
                }
                rows.add(rowOf(csvRecord, columns));
            }
        }
        return rows;
    }

    /** UTF-8 when the bytes are valid UTF-8 (with or without a BOM), else Windows-1252. */
    static Charset charsetOf(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException notUtf8) {
            return WINDOWS_1252;
        }
    }

    private static String decode(byte[] bytes, Charset charset) {
        return new String(bytes, charset);
    }

    /** {@code ;} unless the header line has more commas than semicolons. */
    static char separatorOf(String headerLine) {
        long semicolons = headerLine.chars().filter(c -> c == ';').count();
        long commas = headerLine.chars().filter(c -> c == ',').count();
        return commas > semicolons ? ',' : ';';
    }

    private static Map<String, Integer> headerOf(CSVRecord header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = PriceListLinks.fold(header.get(i).replace("\uFEFF", "")).replace(' ', '_');
            if (name.isEmpty()) {
                continue;
            }
            if (!HEADER.contains(name)) {
                throw new FieldBusinessException(FIELD_FILE, "billing.priceList.import.headerUnknown",
                    header.get(i).trim(), String.join(";", HEADER));
            }
            if (columns.putIfAbsent(name, i) != null) {
                throw new FieldBusinessException(FIELD_FILE, "billing.priceList.import.headerDuplicate", name);
            }
        }
        for (String required : REQUIRED) {
            if (!columns.containsKey(required)) {
                throw new FieldBusinessException(FIELD_FILE, "billing.priceList.import.headerMissing", required,
                    String.join(";", HEADER));
            }
        }
        return columns;
    }

    private static boolean isBlank(CSVRecord csvRecord) {
        for (String value : csvRecord) {
            if (!value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static Row rowOf(CSVRecord csvRecord, Map<String, Integer> columns) {
        Map<String, String> cells = new HashMap<>();
        for (Map.Entry<String, Integer> column : columns.entrySet()) {
            int index = column.getValue();
            cells.put(column.getKey(), index < csvRecord.size() ? clean(csvRecord.get(index)) : "");
        }
        boolean extra = false;
        for (int i = 0; i < csvRecord.size(); i++) {
            if (!columns.containsValue(i) && !csvRecord.get(i).isBlank()) {
                extra = true;
                break;
            }
        }
        // The record number counts the header as record 1, as a spreadsheet numbers its lines.
        return new Row((int) csvRecord.getRecordNumber(), cells, extra);
    }

    /**
     * Trimmed, non-breaking spaces at either end included. Inside the cell nothing changes: a label is kept as
     * typed (the price reads its own grouping spaces).
     */
    private static String clean(String cell) {
        return EDGE_SPACES.matcher(cell).replaceAll("");
    }

    /** A cell a spreadsheet would run as a formula (CSV injection). */
    public static boolean isFormula(String cell) {
        return CsvCells.isFormula(cell);
    }

    /**
     * A whole-franc price: "2000", "2 000", "2 000", "2000,00". Refuses, naming {@code prix}: a blank cell, a
     * negative price, any non-zero decimal, and anything that is not a number (including "2.000", which could mean
     * two thousand or two).
     */
    public static BigDecimal parsePrice(String raw) {
        String compact = raw == null ? "" : raw.replace('\u00A0', ' ').replace('\u202F', ' ').replace(" ", "");
        if (compact.isEmpty()) {
            throw new FieldBusinessException(COL_PRICE, "billing.priceList.priceRequired");
        }
        Matcher number = NUMBER.matcher(compact);
        if (!number.matches()) {
            throw new FieldBusinessException(COL_PRICE, "billing.priceList.import.priceFormat", raw.trim());
        }
        if (!number.group(1).isEmpty()) {
            throw new FieldBusinessException(COL_PRICE, "billing.priceList.priceNegative");
        }
        String decimals = number.group(3);
        if (decimals != null && !ZEROS.matcher(decimals).matches()) {
            // Three digits after the mark may be a thousands group ("2.500"): never guessed.
            throw decimals.length() == 3
                ? new FieldBusinessException(COL_PRICE, "billing.priceList.import.priceFormat", raw.trim())
                : new FieldBusinessException(COL_PRICE, "billing.priceList.priceWhole");
        }
        return new BigDecimal(number.group(2));
    }

    /** {@code JJ/MM/AAAA} (or {@code J/M/AAAA}) or ISO; null when blank (the caller uses today). */
    public static LocalDate parseDate(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return value.contains("/") ? LocalDate.parse(value, FR_DATE) : LocalDate.parse(value);
        } catch (DateTimeParseException notADate) {
            throw new FieldBusinessException(COL_DATE, "billing.priceList.import.dateFormat", value);
        }
    }

    // ───────────────────────────── writing ─────────────────────────────

    /**
     * The export: UTF-8 with a BOM (French Excel opens it with its accents), {@code ;}, CRLF, the import's header,
     * whole-franc prices with no grouping and {@code JJ/MM/AAAA} dates, so the file re-imports unchanged. A cell that
     * a spreadsheet would run as a formula is written behind an apostrophe.
     */
    public static byte[] write(List<ExportRow> rows) {
        StringWriter out = new StringWriter();
        out.write('\uFEFF');
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(';').setRecordSeparator("\r\n").get();
        try (CSVPrinter printer = new CSVPrinter(out, format)) {
            printer.printRecord(HEADER);
            for (ExportRow row : rows) {
                printer.printRecord(
                    CsvCells.safe(row.code()),
                    CsvCells.safe(row.label()),
                    CsvCells.safe(row.category()),
                    Long.toString(row.price().longValueExact()), // whole francs, no grouping: the import's number
                    row.effectiveFrom().format(FR_DATE_OUT),
                    CsvCells.safe(row.linkType()),
                    CsvCells.safe(row.linkKey()));
            }
        } catch (IOException cannotHappen) {
            throw new UncheckedIOException(cannotHappen);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
}
