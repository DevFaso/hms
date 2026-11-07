package com.example.hms.enums;

/**
 * QA D1424: the unit a glucose was entered in. The column stays whole mg/dL, the API's
 * field since the start; the factor is the portal's ({@code shared/glucose-units.ts}).
 */
public enum GlucoseUnit {
    MG_DL(1.0),
    MMOL_L(18.016);

    private final double mgDlPerUnit;

    GlucoseUnit(double mgDlPerUnit) {
        this.mgDlPerUnit = mgDlPerUnit;
    }

    public int toMgDl(double value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.round(value * mgDlPerUnit));
    }
}
