package com.example.hms.utility;

import com.example.hms.enums.JobTitle;

import java.util.Locale;

/**
 * QA D1136 (round 20): a nursing note's author credentials were stored as the
 * job title humanised in English ("Nurse", from {@code NURSE}), so French and
 * Spanish chart reviews read "Roger Bationo, Nurse" and "(Nurse)".
 *
 * <p>A job title now travels as its code ({@code NURSE}); the portal labels it
 * in the reader's language ({@code PORTAL.ENUM.JOB_TITLE.*}). Rows written
 * before carry the English words: {@link #toCode} maps them back to the code.
 * Anything that is not a job title (a licence number, credentials a clinician
 * typed such as "MD, FWACP") is returned as it was written.
 */
public final class JobTitleCredentials {

    private JobTitleCredentials() {
    }

    /** The code of the job title {@code credentials} names, else {@code credentials} trimmed. */
    public static String toCode(String credentials) {
        if (credentials == null) {
            return null;
        }
        String trimmed = credentials.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (JobTitle title : JobTitle.values()) {
            if (title.name().equalsIgnoreCase(trimmed)
                || title.getTitle().equalsIgnoreCase(trimmed)
                || humanised(title).equalsIgnoreCase(trimmed)) {
                return title.name();
            }
        }
        return trimmed;
    }

    /** {@code NURSE_PRACTITIONER} as the old writers spelled it: "Nurse Practitioner". */
    private static String humanised(JobTitle title) {
        StringBuilder out = new StringBuilder();
        for (String part : title.name().toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
