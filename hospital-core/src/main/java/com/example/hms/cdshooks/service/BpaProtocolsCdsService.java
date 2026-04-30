package com.example.hms.cdshooks.service;

import com.example.hms.cdshooks.bpa.BpaRuleEngine;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsServiceDescriptor;
import com.example.hms.model.Patient;
import com.example.hms.repository.PatientRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * CDS Hooks 1.0 {@code patient-view} service that runs the
 * {@link BpaRuleEngine} (malaria, sepsis qSOFA, OB hemorrhage) when a
 * provider opens a chart. Returns one card per fired rule; the front-end
 * Best-Practice Advisory panel renders them as a flat advisory list.
 *
 * <p>Co-exists with {@link PatientViewCdsService} (active allergies +
 * problems summary) on the same hook; CDS Hooks 1.0 allows multiple
 * services per hook and clients invoke each by its discrete id.
 */
@Component
public class BpaProtocolsCdsService implements CdsHookService {

    private static final String ID = "hms-bpa-protocols";

    private final BpaRuleEngine ruleEngine;
    private final PatientRepository patientRepository;

    public BpaProtocolsCdsService(BpaRuleEngine ruleEngine,
                                  PatientRepository patientRepository) {
        this.ruleEngine = ruleEngine;
        this.patientRepository = patientRepository;
    }

    @Override
    public CdsServiceDescriptor descriptor() {
        return new CdsServiceDescriptor(
            "patient-view",
            ID,
            "Best-Practice Advisories",
            "Runs the HMS BPA rule engine (malaria, sepsis qSOFA, OB hemorrhage) "
                + "against the patient's recent vitals, active problems, and active "
                + "prescriptions. Cards are advisory only.",
            null
        );
    }

    @Override
    public CdsHookResponse evaluate(CdsHookRequest request) {
        UUID patientId = CdsHookContext.requirePatientId(request);
        if (patientId == null) return CdsHookResponse.empty();
        Patient patient = patientRepository.findByIdUnscoped(patientId).orElse(null);
        if (patient == null) return CdsHookResponse.empty();

        UUID hospitalId = patient.getHospitalId();
        List<CdsCard> cards = ruleEngine.evaluateForPatient(patient, hospitalId);
        return CdsHookResponse.of(cards);
    }
}
