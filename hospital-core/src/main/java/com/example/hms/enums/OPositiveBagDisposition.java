package com.example.hms.enums;

/**
 * What became of the bag of ONE emergency O RhD positive release (V380). Written once, on the release's own row:
 * a bag that goes out again is a new release with its own row, and says nothing more about this one.
 */
public enum OPositiveBagDisposition {
    /** Hung on the request it was released against: the patient received it. */
    ADMINISTERED,
    /** Back in the laboratory, or discarded, without being hung: nobody was exposed. */
    RETURNED_UNUSED,
    /** The same bag was released again before this release was given: the new release carries what happens next. */
    SUPERSEDED
}
