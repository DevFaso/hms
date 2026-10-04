package com.bitnesttechs.hms.patient.features.messages

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Chat times as a patient reads them. The backend sends
 * `ChatConversationSummaryDTO.lastMessageTimestamp` and the message
 * `timestamp` as an ISO local date-time with no offset ("2026-09-19T10:30:00",
 * the server's wall clock), and the inbox printed that string verbatim.
 *
 * With no offset on the wire there is nothing to convert, so the value is
 * read as a wall-clock time as-is; a value that does carry an offset is
 * moved into [zone] first. Anything unparseable renders as nothing rather
 * than as the machine string.
 */
object ChatTime {

    fun parse(raw: String?, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { LocalDateTime.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zone).toLocalDateTime() }.getOrNull()
    }

    /**
     * The inbox row: the time for today, [yesterday] for yesterday, the
     * weekday within the last week, the date beyond that. Null when there is
     * no usable time.
     */
    fun inboxLabel(
        raw: String?,
        now: LocalDateTime,
        locale: Locale,
        yesterday: String,
        zone: ZoneId = ZoneId.systemDefault()
    ): String? {
        val time = parse(raw, zone) ?: return null
        val days = ChronoUnit.DAYS.between(time.toLocalDate(), now.toLocalDate())
        return when {
            days <= 0L && time.toLocalDate() == now.toLocalDate() -> shortTime(time, locale)
            days == 1L -> yesterday
            days in 2L..6L -> weekday(time.toLocalDate(), locale)
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(time)
        }
    }

    /** A message bubble: the time for today's messages, the date and time otherwise. */
    fun bubbleLabel(
        raw: String?,
        now: LocalDateTime,
        locale: Locale,
        zone: ZoneId = ZoneId.systemDefault()
    ): String? {
        val time = parse(raw, zone) ?: return null
        return if (time.toLocalDate() == now.toLocalDate()) {
            shortTime(time, locale)
        } else {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(time)
        }
    }

    private fun shortTime(time: LocalDateTime, locale: Locale): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time)

    private fun weekday(date: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofPattern("EEEE", locale).format(date)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}
