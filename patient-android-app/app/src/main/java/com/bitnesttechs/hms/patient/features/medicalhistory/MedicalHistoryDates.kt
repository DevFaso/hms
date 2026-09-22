package com.bitnesttechs.hms.patient.features.medicalhistory

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Date rendering for the history screen, kept free of Compose so it can be
 * tested. The web's `date` pipe converts a timestamp to the browser's zone
 * before printing the date, so a diagnosis stamped late at night in the
 * server's zone must not shift a day here either.
 */
object MedicalHistoryDates {
    private const val ABSENT = "-"

    /** A date-only field ("2026-09-21") in the locale's short style; "-" when absent, the raw text when it does not parse. */
    fun formatDate(iso: String?, locale: Locale = Locale.getDefault()): String {
        if (iso.isNullOrBlank()) return ABSENT
        return runCatching { LocalDate.parse(iso.take(10)).format(shortDate(locale)) }.getOrDefault(iso)
    }

    /**
     * A timestamp with an offset ("2026-09-21T23:30:00+00:00"), converted to
     * [zone] first, then printed as a short date; "-" when absent, the raw text
     * when it does not parse.
     */
    fun formatDateTime(
        iso: String?,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault()
    ): String {
        if (iso.isNullOrBlank()) return ABSENT
        return runCatching {
            OffsetDateTime.parse(iso).atZoneSameInstant(zone).toLocalDate().format(shortDate(locale))
        }.getOrDefault(iso)
    }

    private fun shortDate(locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
}
