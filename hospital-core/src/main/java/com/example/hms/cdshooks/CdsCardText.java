package com.example.hms.cdshooks;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.text.MessageFormat;
import java.util.Locale;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.text.NumberFormat;

/**
 * The words on a CDS card, in the caller's language (D61).
 *
 * <p>Card summaries and details used to be English string concatenation, so
 * a French clinician opening a chart read "1 active allergy on file" in an
 * otherwise French screen. Each sentence now has a {@code cds.*} key in the
 * message bundles; the English text stays in code as the default, so a
 * missing key — or a rule built with {@code new} in a unit test, where no
 * bundle is wired — still produces a readable card.
 *
 * <p>Numbers are passed in already formatted (as strings) so MessageFormat
 * never regroups a dose like 1250.00 into "1 250".
 */
@Component
public class CdsCardText {

    /** English-only instance for rules constructed outside Spring. */
    public static final CdsCardText ENGLISH = new CdsCardText(null);

    @Nullable
    private final MessageSource messageSource;

    public CdsCardText(@Nullable MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** The sentence for {@code key} in the request locale, else {@code english} formatted with {@code args}. */
    public String get(String key, String english, Object... args) {
        String pattern = english.replace("'", "''");
        if (messageSource == null) {
            return new MessageFormat(pattern, Locale.ENGLISH).format(args);
        }
        return messageSource.getMessage(key, args, pattern, LocaleContextHolder.getLocale());
    }

    /**
     * QA D1049 (round 18): a date on a card in the reader's language
     * ("30 oct. 2026"), never the ISO "2026-10-30" a {@code toString()} gives.
     * Every card date goes through here; {@code CdsCardLocaleFormatGuardTest}
     * fails on a date or decimal formatted any other way in a CDS/BPA class.
     */
    public String date(@Nullable LocalDate value) {
        if (value == null) {
            return "—";
        }
        return value.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale()));
    }

    /**
     * QA D1078 / D1002 class (round 18): a measured value on a card with the
     * reader's decimal separator ("38,9" in French and Spanish), never the
     * {@code Locale.ROOT} point. No grouping, so a dose is never regrouped.
     */
    public String decimal(@Nullable Number value, int fractionDigits) {
        if (value == null) {
            return "—";
        }
        NumberFormat format = NumberFormat.getNumberInstance(locale());
        format.setGroupingUsed(false);
        format.setMinimumFractionDigits(fractionDigits);
        format.setMaximumFractionDigits(fractionDigits);
        return format.format(value);
    }

    /** The language the card is written in: the request's, or English outside Spring. */
    private Locale locale() {
        return messageSource == null ? Locale.ENGLISH : LocaleContextHolder.getLocale();
    }

    /** A severity / status enum in words: {@code cds.enum.<NAME>}, else its lower-case name. */
    public String enumWord(@Nullable Enum<?> value) {
        if (value == null) {
            return "";
        }
        String fallback = value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return get("cds.enum." + value.name(), fallback);
    }
}
