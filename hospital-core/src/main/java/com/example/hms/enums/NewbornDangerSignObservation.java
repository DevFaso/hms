package com.example.hms.enums;

/**
 * CR-05: what the clinician observed about one danger sign. Blank means not assessed, never absent.
 * ABSENT means the sign was looked for and not found: for "absent spontaneous movement" that is a
 * baby who does move.
 */
public enum NewbornDangerSignObservation {
    PRESENT,
    ABSENT,
    NOT_ASSESSED;

    public static NewbornDangerSignObservation orNotAssessed(NewbornDangerSignObservation observation) {
        return observation == null ? NOT_ASSESSED : observation;
    }

    /** Whether this says anything about the baby: not assessed records nothing. */
    public static boolean isRecorded(NewbornDangerSignObservation observation) {
        return observation == PRESENT || observation == ABSENT;
    }
}
