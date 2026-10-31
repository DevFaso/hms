package com.example.hms.cdshooks;

import com.example.hms.repository.HospitalRepository;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.text.MessageFormat;
import java.util.Locale;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
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

    /** "31/10/2026 20:54": a card's date and time, on the hospital's wall clock. */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /**
     * QA D1114 (round 19): the hospital a card's times are read in. Optional
     * so a rule built with {@code new} in a unit test keeps the server zone.
     */
    @Nullable
    private HospitalRepository hospitals;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHospitals(@Nullable HospitalRepository hospitals) {
        this.hospitals = hospitals;
    }

    /**
     * QA D1114 (round 19, the D1084 class): the zone a card's times are
     * written in - the hospital's ({@code HospitalTime.zoneOf}: its
     * organisation's zone), else the server's when the hospital is unknown
     * (a super-admin in global view, a rule outside Spring).
     */
    public ZoneId zoneOf(@Nullable UUID hospitalId) {
        if (hospitalId == null || hospitals == null) {
            return ZoneId.systemDefault();
        }
        return hospitals.findById(hospitalId)
            .map(com.example.hms.utility.HospitalTime::zoneOf)
            .orElse(ZoneId.systemDefault());
    }

    /**
     * QA D1114 (round 19): a server time (stored in the server's zone, UTC on
     * the hosts) on the hospital's wall clock, so the BPA card says 20:54 when
     * the vitals tab does, not 00:54. Every card time goes through here
     * ({@code ServerComposedTimeGuardTest}).
     */
    public String dateTime(@Nullable LocalDateTime serverTime, ZoneId zone) {
        if (serverTime == null) {
            return "—";
        }
        return com.example.hms.utility.HospitalTime.at(serverTime, zone).format(DATE_TIME);
    }

    /** The hospital's calendar day of a server time, in the reader's language (QA D1114, D1049). */
    public String date(@Nullable LocalDateTime serverTime, ZoneId zone) {
        if (serverTime == null) {
            return "—";
        }
        return date(com.example.hms.utility.HospitalTime.at(serverTime, zone).toLocalDate());
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
