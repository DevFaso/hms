package com.example.hms.enums;

/**
 * Why a cross-hospital read was permitted or refused. One value per rule in
 * {@code RecordAccessPolicy}, in the order the rules are evaluated, so an
 * audit row and a portal message can name the exact gate that closed.
 */
public enum RecordAccessDenialReason {

    /** Every gate passed. */
    PERMITTED,

    /** The acting hospital does not exist or is not active. */
    HOSPITAL_UNKNOWN,

    /**
     * The acting facility is a provider (a PHARMACY or LABORATORY), not a
     * hospital: no chart is read there, whatever staff row, registration or
     * break-the-glass session exists (provider plan §3.3, AC-9). Evaluated
     * before every other gate.
     */
    PROVIDER_FACILITY,

    /**
     * The acting hospital is {@code SCHEMA}-isolated (V97). Its tables cannot
     * be read across by construction, and must not be; sharing stays an
     * explicit export via referral or ROI.
     */
    SCHEMA_ISOLATED_TENANT,

    /** The acting hospital runs {@link RecordAccessPosture#EXPLICIT_CONSENT}. */
    HOSPITAL_REQUIRES_CONSENT,

    /** The patient has excluded their record from cross-hospital reads (E8 #52). */
    PATIENT_OPTED_OUT,

    /** The actor holds no active staff record at the acting hospital. */
    NOT_STAFF_AT_HOSPITAL,

    /** No carrier establishes a live relationship between patient and hospital. */
    NO_TREATMENT_RELATIONSHIP,
    /**
     * The hospital administrator restricted this chart (E8 #54: VIP, staff
     * member, own or family record) and the actor holds no live
     * break-the-glass session for it. Evaluated after a relationship is
     * found, so it never discloses whether one exists to a stranger.
     */
    CHART_RESTRICTED
}
