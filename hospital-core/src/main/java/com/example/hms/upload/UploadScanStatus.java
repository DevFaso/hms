package com.example.hms.upload;

/** The state of a stored upload's malware check. Only {@link #CLEAN} is served. */
public enum UploadScanStatus {
    /** Kept, not served: the check has not answered yet. */
    PENDING,
    CLEAN,
    /** Kept for the record, never served. */
    INFECTED
}
