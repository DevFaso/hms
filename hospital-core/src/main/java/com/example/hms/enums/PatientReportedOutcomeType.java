package com.example.hms.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PatientReportedOutcomeType {
    PAIN_SCORE("Pain Score"),
    MOOD("Mood"),
    ENERGY_LEVEL("Energy Level"),
    SLEEP_QUALITY("Sleep Quality"),
    ANXIETY_LEVEL("Anxiety Level"),
    FATIGUE("Fatigue"),
    BREATHLESSNESS("Breathlessness"),
    NAUSEA("Nausea"),
    APPETITE("Appetite"),
    GENERAL_WELLBEING("General Wellbeing");

    private final String label;
}
