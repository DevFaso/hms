package com.example.hms.enums;

/**
 * Types of prenatal ultrasound scans performed during pregnancy.
 * Aligned with standard prenatal care protocols.
 */
public enum UltrasoundScanType {
    /**
     * First-trimester scan at 11-13 weeks to measure nuchal translucency,
     * confirm due date, determine number of fetuses, and screen for chromosomal abnormalities.
     */
    NUCHAL_TRANSLUCENCY,

    /**
     * Second-trimester anatomy scan at 18-22 weeks with maternal-fetal medicine
     * to check fetal anatomy, amniotic fluid, blood-flow patterns, placenta, cervical length.
     */
    ANATOMY_SCAN,

    /**
     * Third-trimester growth scan to assess fetal size, position, and well-being.
     */
    GROWTH_SCAN,

    /**
     * Biophysical profile to assess fetal well-being through movement, tone, breathing, and amniotic fluid.
     */
    BIOPHYSICAL_PROFILE,

    /**
     * Doppler ultrasound to assess blood flow in umbilical cord, placenta, or fetal organs.
     */
    DOPPLER_STUDY,

    /**
     * Cervical length assessment for preterm labor risk.
     */
    CERVICAL_LENGTH,

    /**
     * Additional ultrasound for high-risk pregnancies or specific clinical indications.
     */
    HIGH_RISK_FOLLOW_UP,

    /**
     * Other specialized ultrasound not covered by standard categories.
     */
    OTHER
}
