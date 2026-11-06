package com.example.hms.service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * QA D1400 + D1401: the one rule that says which filters open a row of the
 * desk's queue. The summary strip counts through it, the queue filters through
 * it, and each row carries the result ({@code queueFilters}), so the portal
 * filters through it too: a card's number is the number of rows it opens.
 *
 * <ul>
 *   <li>A row is opened by its own computed status.</li>
 *   <li>{@value #SCHEDULED} also opens every visit still expected: booked and
 *       not here yet, confirmed or not ({@link #EXPECTED}).</li>
 *   <li>{@value #ARRIVED} also opens an appointment marked CHECKED_IN.</li>
 *   <li>{@value #WALK_IN} opens every visit with no appointment, whatever its
 *       status: a walk-in's status is never WALK_IN once it is on the queue
 *       (it is ARRIVED, IN_PROGRESS, COMPLETED, ...), so a card or a filter
 *       keyed on the status opened nothing.</li>
 * </ul>
 * A walk-in who has arrived is therefore on both the arrived and the walk-in
 * cards: the cards are ways to look at the queue, not parts of a total.
 */
final class ReceptionQueueFilters {

    static final String ALL = "ALL";
    static final String SCHEDULED = "SCHEDULED";
    static final String ARRIVED = "ARRIVED";
    static final String WALK_IN = "WALK_IN";

    /** The computed statuses of a booked visit whose patient is not here yet. */
    static final Set<String> EXPECTED = Set.of(SCHEDULED, "CONFIRMED", "PENDING", "RESCHEDULED");

    /** The computed statuses of a visit that did not take place: nothing about it is the desk's to fix today. */
    private static final Set<String> DID_NOT_TAKE_PLACE = Set.of("CANCELLED", "NO_SHOW", "LEFT_WITHOUT_BEING_SEEN");

    private ReceptionQueueFilters() {
    }

    /** The filters that open a row with this computed status. */
    static Set<String> of(String computedStatus, boolean walkIn) {
        Set<String> filters = new LinkedHashSet<>();
        filters.add(computedStatus);
        if (EXPECTED.contains(computedStatus)) {
            filters.add(SCHEDULED);
        }
        if ("CHECKED_IN".equals(computedStatus)) {
            filters.add(ARRIVED);
        }
        if (walkIn) {
            filters.add(WALK_IN);
        }
        return Collections.unmodifiableSet(filters);
    }

    /** Whether this filter (null and ALL open everything) opens a row carrying these filters. */
    static boolean opens(String filter, Set<String> rowFilters) {
        return filter == null || ALL.equalsIgnoreCase(filter) || rowFilters.contains(filter);
    }

    /** Whether a coverage problem on this row is one the desk has to deal with (the insurance card and flag). */
    static boolean expectedAtTheDesk(String computedStatus) {
        return !DID_NOT_TAKE_PLACE.contains(computedStatus);
    }
}
