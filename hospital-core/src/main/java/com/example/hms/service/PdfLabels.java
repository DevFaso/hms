package com.example.hms.service;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * The labels and date formats of one PDF document's locale, shared by the patient record,
 * wristband/label and invoice renderers. Built once at each renderer's entry point and passed
 * to every drawing helper, so no helper reaches for the request on its own: an invoice drawn
 * from a job has no request, and the fallback then belongs in one place.
 *
 * <p>Dates use the locale's medium style (28 janv. 1985 / Jan 28, 1985 / 28 ene 1985):
 * unambiguous in each language and, unlike the short style, never a two-digit year on a date
 * of birth. Month abbreviations in the three supported languages are all WinAnsi glyphs, which
 * the standard Helvetica faces can draw.
 */
final class PdfLabels {

    private final MessageSource messages;
    private final Locale locale;
    private final DateTimeFormatter date;
    private final DateTimeFormatter dateTime;

    private PdfLabels(MessageSource messages, Locale locale) {
        this.messages = messages;
        this.locale = locale;
        this.date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
        this.dateTime = new DateTimeFormatterBuilder()
            .appendLocalized(FormatStyle.MEDIUM, null)
            .appendLiteral(' ')
            .appendPattern("HH:mm")
            .toFormatter(locale);
    }

    /** Labels for an explicit locale; French, the product's first language, when none is given. */
    static PdfLabels of(MessageSource messages, Locale locale) {
        return new PdfLabels(messages, locale != null ? locale : Locale.FRENCH);
    }

    /**
     * Labels for the request being served, read once here and never again downstream. Outside
     * a request (no locale context at all: a job, a test) French, rather than the JVM default.
     */
    static PdfLabels ofRequest(MessageSource messages) {
        LocaleContext context = LocaleContextHolder.getLocaleContext();
        return of(messages, context != null ? context.getLocale() : null);
    }

    Locale locale() {
        return locale;
    }

    String get(String key, Object... args) {
        return messages.getMessage(key, args, locale);
    }

    /** A bundle label for a code, or the code itself when the bundle has no entry for it. */
    String getOrCode(String key, String code) {
        return messages.getMessage(key, null, code, locale);
    }

    String fmt(LocalDateTime t) {
        return t == null ? "" : dateTime.format(t);
    }

    String fmt(LocalDate d) {
        return d == null ? "" : date.format(d);
    }
}
