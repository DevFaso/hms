package com.example.hms.enums;

/**
 * Who is asking for the records (Tier 2 item 39b). THIRD_PARTY covers
 * guardians, insurers, lawyers and other facilities — the free-text
 * requester fields carry the specifics; an enum of third-party kinds would
 * be a taxonomy nobody asked for.
 */
public enum RoiRequesterType {
    PATIENT,
    THIRD_PARTY
}
