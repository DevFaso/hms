package com.example.hms.enums;

/**
 * Severity levels for drug-drug interactions.
 * Based on clinical significance and potential patient harm.
 */
public enum InteractionSeverity {
    /**
     * Contraindicated - combination should never be used together.
     * Risk of serious adverse outcomes or death.
     */
    CONTRAINDICATED,

    /**
     * Major - highly clinically significant. May result in serious adverse outcomes.
     * Requires immediate clinical intervention or dose adjustment.
     * Examples: warfarin + aspirin (bleeding risk), MAO inhibitors + SSRIs (serotonin syndrome)
     */
    MAJOR,

    /**
     * Moderate - clinically significant. May result in exacerbation of condition or side effects.
     * Monitoring or dose adjustment usually required.
     * Examples: calcium channel blockers + beta blockers (bradycardia)
     */
    MODERATE,

    /**
     * Minor - clinical significance is limited. Effects are usually mild.
     * No specific intervention typically required, but patient should be aware.
     */
    MINOR,

    /**
     * Unknown - interaction has been reported but clinical significance is not well established.
     * Requires clinical judgment.
     */
    UNKNOWN
}
