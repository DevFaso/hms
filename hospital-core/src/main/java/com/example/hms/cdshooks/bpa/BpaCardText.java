package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.CdsCardText;
import com.example.hms.model.BpaProtocol;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * QA D217: the shared words of a Best-Practice Advisory card. A protocol's
 * name and summary are seeded in English (V64, V130); each has a
 * {@code cds.bpa.<CODE>.name|summary} bundle key, with the seeded text as the
 * fallback so an edited protocol still reads.
 */
final class BpaCardText {

    private BpaCardText() {
    }

    static String name(CdsCardText text, BpaProtocol protocol) {
        return text.get("cds.bpa." + protocol.getProtocolCode() + ".name", nullToEmpty(protocol.getName()));
    }

    static String summary(CdsCardText text, BpaProtocol protocol) {
        return text.get("cds.bpa." + protocol.getProtocolCode() + ".summary", nullToEmpty(protocol.getSummary()));
    }

    static String yesNo(CdsCardText text, boolean value) {
        return value ? text.get("cds.bpa.yes", "yes") : text.get("cds.bpa.no", "no");
    }

    /**
     * QA D1114 (round 19): a reading's time on the hospital's wall clock. It
     * was formatted as stored, the server's UTC, so a malaria card read
     * "31/10/2026 00:54" for a temperature the vitals tab showed at 20:54.
     */
    static String when(CdsCardText text, LocalDateTime at, ZoneId hospitalZone) {
        return text.dateTime(at, hospitalZone);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
