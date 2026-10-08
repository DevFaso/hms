package com.example.hms.enums;

/**
 * G13: which rows the pharmacy work queue lists, by claim.
 * <ul>
 *   <li>{@link #ALL}: every queue row (the default);</li>
 *   <li>{@link #MINE}: rows with an active claim by the caller;</li>
 *   <li>{@link #UNCLAIMED}: rows with no active claim and no open
 *       preparation, the rows someone could pick up now.</li>
 * </ul>
 */
public enum QueueClaimFilter {
    ALL,
    MINE,
    UNCLAIMED
}
