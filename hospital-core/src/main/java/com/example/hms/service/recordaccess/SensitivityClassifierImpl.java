package com.example.hms.service.recordaccess;

import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.Admission;
import com.example.hms.model.Consultation;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.NursingNote;
import com.example.hms.model.PatientProblem;
import org.springframework.stereotype.Service;

@Service
public class SensitivityClassifierImpl implements SensitivityClassifier {

    @Override
    public SensitivityCategory effectiveCategory(SensitivityCategory explicit, Department department) {
        if (explicit != null) {
            return explicit;
        }
        return department == null ? null : department.getDefaultSensitivityCategory();
    }

    @Override
    public SensitivityCategory effectiveCategory(Encounter encounter) {
        if (encounter == null) {
            return null;
        }
        return effectiveCategory(encounter.getSensitivityCategory(), encounter.getDepartment());
    }

    @Override
    public SensitivityCategory effectiveCategory(Admission admission) {
        if (admission == null) {
            return null;
        }
        return effectiveCategory(admission.getSensitivityCategory(), admission.getDepartment());
    }

    @Override
    public SensitivityCategory effectiveCategory(Consultation consultation) {
        if (consultation == null) {
            return null;
        }
        Encounter encounter = consultation.getEncounter();
        Department department = encounter == null ? null : encounter.getDepartment();
        // The consultation's own tag wins; failing that it inherits the
        // encounter's explicit tag, and only then the department default —
        // otherwise a consultation inside a psychiatric encounter would look
        // untagged whenever the encounter was tagged by hand rather than by
        // its department.
        SensitivityCategory explicit = consultation.getSensitivityCategory();
        if (explicit == null && encounter != null) {
            explicit = encounter.getSensitivityCategory();
        }
        return effectiveCategory(explicit, department);
    }

    @Override
    public SensitivityCategory effectiveCategory(PatientProblem problem) {
        return problem == null ? null : problem.getSensitivityCategory();
    }

    @Override
    public SensitivityCategory effectiveCategory(NursingNote note) {
        return note == null ? null : note.getSensitivityCategory();
    }

    @Override
    public boolean travelsCrossHospital(SensitivityCategory effectiveCategory) {
        // Default-withhold. Every named category is one a jurisdiction
        // protects separately; none of them rides on the treatment
        // presumption. Written as "untagged only" rather than a deny-list so
        // a category added to the enum later is withheld by default instead
        // of silently travelling until someone remembers to list it.
        return effectiveCategory == null;
    }
}
