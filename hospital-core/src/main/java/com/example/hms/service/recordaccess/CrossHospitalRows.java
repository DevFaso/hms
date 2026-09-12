package com.example.hms.service.recordaccess;

import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.Hospital;

import java.util.UUID;

/**
 * The one rule for whether a clinical row recorded at another hospital may be
 * shown here (E9 #59, decision D3): a row from the acting hospital always
 * surfaces; a foreign row surfaces only when its effective sensitivity
 * category is unset. A foreign row that IS categorised (HIV, behavioural
 * health, substance use, reproductive health) is withheld — it opens through
 * break-the-glass with a stated reason (E9 #62), never automatically.
 *
 * <p>Untagged travels. That is Epic's behaviour and it is what the decision
 * chose; the classification is by department default and is the hospital's
 * job at onboarding (E9 #63).
 */
public final class CrossHospitalRows {

    private CrossHospitalRows() {
    }

    public static boolean maySurface(Hospital rowHospital, UUID actingHospitalId, SensitivityCategory category) {
        return maySurface(rowHospital != null ? rowHospital.getId() : null, actingHospitalId, category);
    }

    public static boolean maySurface(UUID rowHospitalId, UUID actingHospitalId, SensitivityCategory category) {
        boolean foreign = rowHospitalId != null && !rowHospitalId.equals(actingHospitalId);
        return !foreign || category == null;
    }
}
