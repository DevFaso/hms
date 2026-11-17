package com.example.hms.repository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * QA CX-002 (round 33): the rows of the super-admin analytics' grouped counts.
 * The counts are computed by the database, one query per metric, whatever the
 * number of hospitals or departments, never by loading the records to count them.
 */
public final class PlatformCountRows {

    private PlatformCountRows() {
    }

    /** How many records one hospital or one department holds. */
    public interface IdCount {
        UUID getOwnerId();

        long getTotal();
    }

    /** How many records carry one status; a null label is a record with no status. */
    public interface StatusCount {
        String getLabel();

        long getTotal();
    }

    /** How many records fall on one day. */
    public interface DayCount {
        LocalDate getDay();

        long getTotal();
    }
}
